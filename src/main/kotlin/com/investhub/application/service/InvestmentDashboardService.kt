package com.investhub.application.service

import com.investhub.application.port.input.GetAssetSummaryUseCase
import com.investhub.application.port.input.GetForeignStockPortfolioUseCase
import com.investhub.application.port.input.GetInvestmentDashboardUseCase
import com.investhub.application.port.input.GetRecommendedProductsUseCase
import com.investhub.application.port.output.AccountPort
import com.investhub.application.port.output.ForeignStockPort
import com.investhub.application.port.output.RecommendationPort
import com.investhub.domain.dashboard.AssetSummaryResult
import com.investhub.domain.dashboard.FailureReason
import com.investhub.domain.dashboard.ForeignStockPortfolioResult
import com.investhub.domain.dashboard.InvestmentDashboard
import com.investhub.domain.dashboard.RecommendedProductsResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeoutException

/**
 * 투자 대시보드 조회 서비스.
 *
 * **하나의 서비스가 네 유스케이스를 모두 구현한다** — 도메인별 단독 조회 3종 + 집계 1종.
 * 집계(`getDashboard`)는 도메인별 유스케이스를 **재사용**하므로 조회·실패 처리 로직이 한 곳에만 존재한다.
 *
 * API 입구는 두 갈래로 갈린다 (ADR-008 참고):
 * - 도메인별 엔드포인트 → 각 유스케이스를 단독 호출 (독립 갱신·속성별 캐시 정책)
 * - 집계 엔드포인트 → 세 유스케이스를 병렬 조합 (첫 화면 1회 렌더)
 *
 * 각 도메인 조회는 [AssetSummaryResult] 등 Sealed Result로 부분 실패를 표현하므로,
 * 단독 호출이든 집계든 동일한 실패 분류(CB/Bulkhead/Timeout)를 공유한다.
 */
@Service
class InvestmentDashboardService(
    private val accountPort: AccountPort,
    private val foreignStockPort: ForeignStockPort,
    private val recommendationPort: RecommendationPort,
    @Qualifier("serviceExecutor") private val executor: ExecutorService,
) : GetInvestmentDashboardUseCase,
    GetAssetSummaryUseCase,
    GetForeignStockPortfolioUseCase,
    GetRecommendedProductsUseCase {
    private val log = KotlinLogging.logger {}

    // ── 도메인별 단독 조회 (입력 유스케이스) — 컨트롤러와 집계가 함께 재사용 ──

    override fun getAssetSummary(userId: String): AssetSummaryResult =
        runCatching {
            AssetSummaryResult.Success(accountPort.getAssetSummary(userId))
        }.getOrElse { ex ->
            log.warn(ex) { "내부 계좌 조회 실패" }
            AssetSummaryResult.Failure(ex.toFailureReason())
        }

    override fun getForeignStockPortfolio(userId: String): ForeignStockPortfolioResult =
        runCatching {
            ForeignStockPortfolioResult.Success(foreignStockPort.getPortfolio(userId))
        }.getOrElse { ex ->
            log.warn(ex) { "해외 주식 조회 실패" }
            ForeignStockPortfolioResult.Failure(ex.toFailureReason())
        }

    override fun getRecommendedProducts(userId: String): RecommendedProductsResult =
        runCatching {
            RecommendedProductsResult.Success(recommendationPort.getRecommendedProducts(userId))
        }.getOrElse { ex ->
            log.warn(ex) { "추천 상품 조회 실패" }
            RecommendedProductsResult.Failure(ex.toFailureReason())
        }

    // ── 집계 (입력 유스케이스) — 위 세 유스케이스를 병렬 재사용 ──

    override fun getDashboard(userId: String): InvestmentDashboard {
        log.info { "대시보드 조회 시작" }

        // MDC(ThreadLocal)는 Virtual Thread로 자동 전파되지 않는다.
        // 호출 시점의 MDC 컨텍스트를 캡처해 각 가상 스레드로 명시적으로 전달한다.
        // 각 어댑터는 이미 자신의 전용 executor를 주입받아 실행하므로
        // 이 serviceExecutor는 Future 제출 전용으로만 사용된다.
        val assetFuture = supplyAsyncWithMdc { getAssetSummary(userId) }
        val stockFuture = supplyAsyncWithMdc { getForeignStockPortfolio(userId) }
        val recommendFuture = supplyAsyncWithMdc { getRecommendedProducts(userId) }

        val dashboard =
            InvestmentDashboard(
                userId = userId,
                assetSummary = assetFuture.join(),
                foreignStockPortfolio = stockFuture.join(),
                recommendedProducts = recommendFuture.join(),
            )

        log.info {
            "대시보드 조회 완료 — " +
                "asset=${dashboard.assetSummary::class.simpleName}, " +
                "stock=${dashboard.foreignStockPortfolio::class.simpleName}, " +
                "recommend=${dashboard.recommendedProducts::class.simpleName}"
        }

        return dashboard
    }

    /**
     * 현재 스레드의 MDC 컨텍스트를 캡처한 뒤, 가상 스레드 내에서 복원하고 실행한다.
     * Virtual Thread는 parent의 ThreadLocal을 상속하지 않으므로 이 래퍼가 필요하다.
     */
    private fun <T> supplyAsyncWithMdc(supplier: () -> T): CompletableFuture<T> {
        val mdcContext = MDC.getCopyOfContextMap() ?: emptyMap()
        return CompletableFuture.supplyAsync({
            mdcContext.forEach(MDC::put)
            try {
                supplier()
            } finally {
                MDC.clear()
            }
        }, executor)
    }

    private fun Throwable.toFailureReason(): FailureReason =
        when (this) {
            is CallNotPermittedException -> FailureReason.CIRCUIT_OPEN
            is BulkheadFullException -> FailureReason.RESOURCE_EXHAUSTED
            is TimeoutException -> FailureReason.TIMEOUT
            else -> FailureReason.SERVICE_UNAVAILABLE
        }
}
