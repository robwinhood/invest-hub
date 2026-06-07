package com.investhub.adapter.`in`.lifecycle

import com.investhub.application.port.input.GetInvestmentDashboardUseCase
import com.investhub.domain.account.AssetSummary
import com.investhub.domain.dashboard.AssetSummaryResult
import com.investhub.domain.dashboard.FailureReason
import com.investhub.domain.dashboard.ForeignStockPortfolioResult
import com.investhub.domain.dashboard.InvestmentDashboard
import com.investhub.domain.dashboard.RecommendedProductsResult
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.boot.context.event.ApplicationReadyEvent
import java.math.BigDecimal

class LocalCacheSeederTest : DescribeSpec() {
    init {
        fun mockDashboard(userId: String) =
            InvestmentDashboard(
                userId = userId,
                assetSummary =
                    AssetSummaryResult.Success(
                        AssetSummary(
                            totalBalance = BigDecimal("1000"),
                            savingsList = emptyList(),
                            investmentList = emptyList(),
                        ),
                    ),
                foreignStockPortfolio = ForeignStockPortfolioResult.Failure(FailureReason.SERVICE_UNAVAILABLE),
                recommendedProducts = RecommendedProductsResult.Success(emptyList()),
            )

        val readyEvent = mockk<ApplicationReadyEvent>(relaxed = true)

        describe("LocalCacheSeeder") {
            it("설정된 데모 사용자마다 getDashboard를 1회씩 호출한다") {
                val useCase = mockk<GetInvestmentDashboardUseCase>()
                every { useCase.getDashboard(any()) } answers { mockDashboard(firstArg()) }

                val seeder = LocalCacheSeeder(useCase, demoUsers = listOf("user-001", "user-002"))
                seeder.onApplicationEvent(readyEvent)

                verify(exactly = 1) { useCase.getDashboard("user-001") }
                verify(exactly = 1) { useCase.getDashboard("user-002") }
            }

            it("일부 사용자 시딩이 실패해도 나머지는 계속 진행되고 예외를 전파하지 않는다") {
                val useCase = mockk<GetInvestmentDashboardUseCase>()
                every { useCase.getDashboard("user-001") } throws RuntimeException("외부 시스템 오류")
                every { useCase.getDashboard("user-002") } answers { mockDashboard("user-002") }

                val seeder = LocalCacheSeeder(useCase, demoUsers = listOf("user-001", "user-002"))

                shouldNotThrow<Exception> { seeder.onApplicationEvent(readyEvent) }
                verify(exactly = 1) { useCase.getDashboard("user-002") }
            }
        }
    }
}
