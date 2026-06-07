package com.investhub.adapter.out.recommendation

import com.investhub.adapter.out.CachingResilientAdapter
import com.investhub.application.port.output.RecommendationPort
import com.investhub.domain.product.InvestmentProduct
import com.investhub.domain.product.ProductType
import com.investhub.domain.product.RiskLevel
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.Cache
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.ExecutorService
import kotlin.random.Random

/**
 * 추천 엔진 어댑터 (Mock).
 * 실 환경에서는 내부 ML 서비스 HTTP/gRPC 호출로 대체.
 *
 * recommendationExecutor 전용 사용 → 스레드 이름: recommendation-vt-N
 * [CachingResilientAdapter]를 상속하므로 캐시(5분 TTL)가 구조적으로 보장된다.
 * 비필수 참고 정보이므로 CB 임계값이 가장 관대하다.
 */
@Component
class RecommendationEngineAdapter(
    circuitBreakerRegistry: CircuitBreakerRegistry,
    bulkheadRegistry: BulkheadRegistry,
    timeLimiterRegistry: TimeLimiterRegistry,
    @Qualifier("recommendationExecutor") executor: ExecutorService,
    @Qualifier("recommendationsCache") cache: Cache,
) : CachingResilientAdapter<List<InvestmentProduct>>(
        circuitBreakerRegistry,
        bulkheadRegistry,
        timeLimiterRegistry,
        executor,
        cache,
    ),
    RecommendationPort {
    private val log = KotlinLogging.logger {}
    override val portName = "recommendation"

    override fun getRecommendedProducts(userId: String): List<InvestmentProduct> = executeWithCache(userId)

    override fun callUncached(userId: String): List<InvestmentProduct> {
        Thread.sleep(Random.nextLong(30, 200))
        log.debug { "추천 엔진 조회 완료" }
        return buildMockRecommendations()
    }

    private fun buildMockRecommendations(): List<InvestmentProduct> =
        listOf(
            InvestmentProduct(
                productId = "PROD-001",
                productType = ProductType.ETF,
                name = "미국 S&P500 ETF",
                description = "미국 500대 기업에 분산 투자하는 대표 ETF. 장기 우상향 기대.",
                expectedReturnRate = BigDecimal("8.50"),
                riskLevel = RiskLevel.MEDIUM,
                minimumAmount = BigDecimal("10000"),
            ),
            InvestmentProduct(
                productId = "PROD-002",
                productType = ProductType.FUND,
                name = "국내 배당주 펀드",
                description = "안정적인 배당 수익을 추구하는 국내 가치주 펀드.",
                expectedReturnRate = BigDecimal("5.20"),
                riskLevel = RiskLevel.LOW,
                minimumAmount = BigDecimal("100000"),
            ),
            InvestmentProduct(
                productId = "PROD-003",
                productType = ProductType.BOND,
                name = "한국 국고채 10년",
                description = "정부 보증 안전 자산. 금리 하락기 시세 차익 기대.",
                expectedReturnRate = BigDecimal("3.80"),
                riskLevel = RiskLevel.VERY_LOW,
                minimumAmount = BigDecimal("1000000"),
            ),
            InvestmentProduct(
                productId = "PROD-004",
                productType = ProductType.FOREIGN_STOCK,
                name = "글로벌 AI 테마 ETF",
                description = "AI 반도체·소프트웨어 기업을 중심으로 한 성장형 ETF.",
                expectedReturnRate = BigDecimal("14.00"),
                riskLevel = RiskLevel.HIGH,
                minimumAmount = BigDecimal("50000"),
            ),
        )
}
