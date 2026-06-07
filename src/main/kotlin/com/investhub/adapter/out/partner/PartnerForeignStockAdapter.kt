package com.investhub.adapter.out.partner

import com.investhub.adapter.out.ResilientAdapter
import com.investhub.application.port.output.ForeignStockPort
import com.investhub.domain.stock.ForeignStockHolding
import com.investhub.domain.stock.ForeignStockPortfolio
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.ExecutorService
import kotlin.random.Random

/**
 * 제휴사 해외 주식 잔고 어댑터 (Mock).
 * 실 환경에서는 제휴사 REST/gRPC 호출로 대체.
 *
 * partnerExecutor 전용 사용 → 스레드 이름: partner-vt-N
 * 제휴사 지연이 내부 계좌나 추천 executor에 영향을 줄 수 없다.
 * 외부 의존성이므로 CB 임계값이 가장 엄격하고 Bulkhead 한도가 가장 낮다.
 */
@Component
class PartnerForeignStockAdapter(
    circuitBreakerRegistry: CircuitBreakerRegistry,
    bulkheadRegistry: BulkheadRegistry,
    timeLimiterRegistry: TimeLimiterRegistry,
    @Qualifier("partnerExecutor") executor: ExecutorService,
) : ResilientAdapter<ForeignStockPortfolio>(circuitBreakerRegistry, bulkheadRegistry, timeLimiterRegistry, executor),
    ForeignStockPort {
    private val log = KotlinLogging.logger {}
    override val portName = "foreign-stock"

    override fun getPortfolio(userId: String): ForeignStockPortfolio = execute { callPartnerApi(userId) }

    private fun callPartnerApi(userId: String): ForeignStockPortfolio {
        Thread.sleep(Random.nextLong(80, 400))
        simulateOccasionalError()
        log.debug { "제휴사 해외 주식 조회 완료" }
        return buildMockPortfolio()
    }

    // 2% 확률로 제휴사 일시 오류 시뮬레이션 (CB 동작 검증용)
    private fun simulateOccasionalError() {
        if (Random.nextInt(100) < 2) {
            throw RuntimeException("파트너 API 일시 오류 (시뮬레이션)")
        }
    }

    private fun buildMockPortfolio(): ForeignStockPortfolio {
        val holdings =
            listOf(
                ForeignStockHolding(
                    ticker = "AAPL",
                    stockName = "Apple Inc.",
                    quantity = BigDecimal("5"),
                    currentPrice = BigDecimal("189.50"),
                    currency = "USD",
                    currentValueInKrw = BigDecimal("1270925"),
                    purchaseValueInKrw = BigDecimal("1100000"),
                    returnRate = BigDecimal("15.54"),
                ),
                ForeignStockHolding(
                    ticker = "MSFT",
                    stockName = "Microsoft Corporation",
                    quantity = BigDecimal("3"),
                    currentPrice = BigDecimal("415.20"),
                    currency = "USD",
                    currentValueInKrw = BigDecimal("1668408"),
                    purchaseValueInKrw = BigDecimal("1500000"),
                    returnRate = BigDecimal("11.23"),
                ),
                ForeignStockHolding(
                    ticker = "NVDA",
                    stockName = "NVIDIA Corporation",
                    quantity = BigDecimal("2"),
                    currentPrice = BigDecimal("132.75"),
                    currency = "USD",
                    currentValueInKrw = BigDecimal("357000"),
                    purchaseValueInKrw = BigDecimal("280000"),
                    returnRate = BigDecimal("27.50"),
                ),
            )
        return ForeignStockPortfolio(
            totalValueInKrw = holdings.sumOf { it.currentValueInKrw },
            holdingList = holdings,
        )
    }
}
