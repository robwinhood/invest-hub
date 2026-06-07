package com.investhub.application.service

import com.investhub.application.port.input.GetInvestmentDashboardUseCase
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

@Service
class InvestmentDashboardService(
    private val accountPort: AccountPort,
    private val foreignStockPort: ForeignStockPort,
    private val recommendationPort: RecommendationPort,
    @Qualifier("serviceExecutor") private val executor: ExecutorService,
) : GetInvestmentDashboardUseCase {
    private val log = KotlinLogging.logger {}

    override fun getDashboard(userId: String): InvestmentDashboard {
        log.info { "대시보드 조회 시작" }

        // MDC(ThreadLocal)는 Virtual Thread로 자동 전파되지 않는다.
        // 호출 시점의 MDC 컨텍스트를 캡처해 각 가상 스레드로 명시적으로 전달한다.
        // 각 어댑터는 이미 자신의 전용 executor를 주입받아 실행하므로
        // 이 serviceExecutor는 Future 제출 전용으로만 사용된다.
        val assetFuture = supplyAsyncWithMdc { fetchAssetSummary(userId) }
        val stockFuture = supplyAsyncWithMdc { fetchForeignStockPortfolio(userId) }
        val recommendFuture = supplyAsyncWithMdc { fetchRecommendedProducts(userId) }

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

    private fun fetchAssetSummary(userId: String): AssetSummaryResult =
        runCatching {
            AssetSummaryResult.Success(accountPort.getAssetSummary(userId))
        }.getOrElse { ex ->
            log.warn(ex) { "내부 계좌 조회 실패" }
            AssetSummaryResult.Failure(ex.toFailureReason())
        }

    private fun fetchForeignStockPortfolio(userId: String): ForeignStockPortfolioResult =
        runCatching {
            ForeignStockPortfolioResult.Success(foreignStockPort.getPortfolio(userId))
        }.getOrElse { ex ->
            log.warn(ex) { "해외 주식 조회 실패" }
            ForeignStockPortfolioResult.Failure(ex.toFailureReason())
        }

    private fun fetchRecommendedProducts(userId: String): RecommendedProductsResult =
        runCatching {
            RecommendedProductsResult.Success(recommendationPort.getRecommendedProducts(userId))
        }.getOrElse { ex ->
            log.warn(ex) { "추천 상품 조회 실패" }
            RecommendedProductsResult.Failure(ex.toFailureReason())
        }

    private fun Throwable.toFailureReason(): FailureReason =
        when (this) {
            is CallNotPermittedException -> FailureReason.CIRCUIT_OPEN
            is BulkheadFullException -> FailureReason.RESOURCE_EXHAUSTED
            is TimeoutException -> FailureReason.TIMEOUT
            else -> FailureReason.SERVICE_UNAVAILABLE
        }
}
