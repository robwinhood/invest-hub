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
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal

class DashboardWarmerTest : DescribeSpec() {
    init {
        fun freshUseCase() = mockk<GetInvestmentDashboardUseCase>(relaxed = true)

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
                foreignStockPortfolio =
                    ForeignStockPortfolioResult.Failure(
                        FailureReason.SERVICE_UNAVAILABLE,
                    ),
                recommendedProducts = RecommendedProductsResult.Success(emptyList()),
            )

        describe("DashboardWarmer") {
            it("repeatCount 횟수만큼 useCase를 호출한다") {
                val useCase = freshUseCase()
                every { useCase.getDashboard(any()) } answers { mockDashboard(firstArg()) }

                val warmer = DashboardWarmer(useCase, repeatCount = 3)
                warmer.warm()

                verify(exactly = 3) { useCase.getDashboard(any()) }
            }

            it("useCase가 예외를 던져도 warm()은 정상 완료된다") {
                val useCase = freshUseCase()
                every { useCase.getDashboard(any()) } throws RuntimeException("외부 시스템 오류")

                val warmer = DashboardWarmer(useCase, repeatCount = 2)

                shouldNotThrow<Exception> { warmer.warm() }
            }

            it("Partial Success 응답도 웜업 성공으로 처리한다") {
                val useCase = freshUseCase()
                every { useCase.getDashboard(any()) } answers { mockDashboard(firstArg()) }

                val warmer = DashboardWarmer(useCase, repeatCount = 1)

                shouldNotThrow<Exception> { warmer.warm() }
                verify(exactly = 1) { useCase.getDashboard(any()) }
            }

            it("name()은 'DashboardWarmer'를 반환한다") {
                val warmer = DashboardWarmer(freshUseCase(), repeatCount = 1)
                warmer.name() shouldBe "DashboardWarmer"
            }

            it("repeatCount=0이면 useCase를 호출하지 않는다") {
                val useCase = freshUseCase()
                val warmer = DashboardWarmer(useCase, repeatCount = 0)
                warmer.warm()

                verify(exactly = 0) { useCase.getDashboard(any()) }
            }
        }
    }
}
