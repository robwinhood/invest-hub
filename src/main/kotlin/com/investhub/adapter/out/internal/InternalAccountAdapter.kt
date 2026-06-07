package com.investhub.adapter.out.internal

import com.investhub.adapter.out.ResilientAdapter
import com.investhub.application.port.output.AccountPort
import com.investhub.domain.account.AccountType
import com.investhub.domain.account.AssetSummary
import com.investhub.domain.account.HoldingType
import com.investhub.domain.account.InvestmentHolding
import com.investhub.domain.account.SavingsAccount
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
 * 내부 원장 어댑터 (Mock).
 * 실 환경에서는 JDBC/JPA 또는 내부 gRPC 호출로 대체.
 *
 * accountExecutor 전용 사용 → 스레드 이름: account-vt-N
 * 제휴사 장애가 이 executor에 영향을 줄 수 없다.
 */
@Component
class InternalAccountAdapter(
    circuitBreakerRegistry: CircuitBreakerRegistry,
    bulkheadRegistry: BulkheadRegistry,
    timeLimiterRegistry: TimeLimiterRegistry,
    @Qualifier("accountExecutor") executor: ExecutorService,
) : ResilientAdapter<AssetSummary>(circuitBreakerRegistry, bulkheadRegistry, timeLimiterRegistry, executor),
    AccountPort {
    private val log = KotlinLogging.logger {}
    override val portName = "account"

    override fun getAssetSummary(userId: String): AssetSummary = execute { queryLedger(userId) }

    private fun queryLedger(userId: String): AssetSummary {
        Thread.sleep(Random.nextLong(10, 80))
        log.debug { "내부 원장 조회 완료" }
        return buildMockAssetSummary(userId)
    }

    private fun buildMockAssetSummary(userId: String): AssetSummary {
        val savings =
            listOf(
                SavingsAccount(
                    accountId = "ACC-$userId-001",
                    accountType = AccountType.SAVINGS,
                    productName = "투자허브 보통예금",
                    balance = BigDecimal("5234567"),
                ),
                SavingsAccount(
                    accountId = "ACC-$userId-002",
                    accountType = AccountType.TIME_DEPOSIT,
                    productName = "투자허브 정기예금 12개월",
                    balance = BigDecimal("10000000"),
                ),
            )
        val investments =
            listOf(
                InvestmentHolding(
                    holdingId = "HOLD-$userId-001",
                    holdingType = HoldingType.FUND,
                    name = "글로벌 혼합형 펀드",
                    currentValue = BigDecimal("5502300"),
                    purchaseValue = BigDecimal("5000000"),
                    returnRate = BigDecimal("10.05"),
                ),
                InvestmentHolding(
                    holdingId = "HOLD-$userId-002",
                    holdingType = HoldingType.BOND,
                    name = "한국 국채 3년",
                    currentValue = BigDecimal("3015000"),
                    purchaseValue = BigDecimal("3000000"),
                    returnRate = BigDecimal("0.50"),
                ),
            )
        return AssetSummary(
            totalBalance = savings.sumOf { it.balance } + investments.sumOf { it.currentValue },
            savingsList = savings,
            investmentList = investments,
        )
    }
}
