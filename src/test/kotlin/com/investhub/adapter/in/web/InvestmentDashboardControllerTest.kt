package com.investhub.adapter.`in`.web

import com.investhub.application.port.input.GetInvestmentDashboardUseCase
import com.investhub.domain.account.AccountType
import com.investhub.domain.account.AssetSummary
import com.investhub.domain.account.HoldingType
import com.investhub.domain.account.InvestmentHolding
import com.investhub.domain.account.SavingsAccount
import com.investhub.domain.dashboard.AssetSummaryResult
import com.investhub.domain.dashboard.FailureReason
import com.investhub.domain.dashboard.ForeignStockPortfolioResult
import com.investhub.domain.dashboard.InvestmentDashboard
import com.investhub.domain.dashboard.RecommendedProductsResult
import com.investhub.domain.product.InvestmentProduct
import com.investhub.domain.product.ProductType
import com.investhub.domain.product.RiskLevel
import com.investhub.domain.stock.ForeignStockHolding
import com.investhub.domain.stock.ForeignStockPortfolio
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal

class InvestmentDashboardControllerTest : DescribeSpec() {
    private val getDashboardUseCase = mockk<GetInvestmentDashboardUseCase>()
    private val controller = InvestmentDashboardController(getDashboardUseCase)
    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller).build()

    init {
        fun successDashboard(userId: String) =
            InvestmentDashboard(
                userId = userId,
                assetSummary =
                    AssetSummaryResult.Success(
                        AssetSummary(
                            totalBalance = BigDecimal("18000000"),
                            savingsList =
                                listOf(
                                    SavingsAccount(
                                        accountId = "ACC-001",
                                        accountType = AccountType.SAVINGS,
                                        productName = "보통예금",
                                        balance = BigDecimal("5000000"),
                                    ),
                                ),
                            investmentList =
                                listOf(
                                    InvestmentHolding(
                                        holdingId = "HOLD-001",
                                        holdingType = HoldingType.FUND,
                                        name = "글로벌 펀드",
                                        currentValue = BigDecimal("5500000"),
                                        purchaseValue = BigDecimal("5000000"),
                                        returnRate = BigDecimal("10.0"),
                                    ),
                                ),
                        ),
                    ),
                foreignStockPortfolio =
                    ForeignStockPortfolioResult.Success(
                        ForeignStockPortfolio(
                            totalValueInKrw = BigDecimal("3000000"),
                            holdingList =
                                listOf(
                                    ForeignStockHolding(
                                        ticker = "AAPL",
                                        stockName = "Apple Inc.",
                                        quantity = BigDecimal("5"),
                                        currentPrice = BigDecimal("189.50"),
                                        currency = "USD",
                                        currentValueInKrw = BigDecimal("1270000"),
                                        purchaseValueInKrw = BigDecimal("1100000"),
                                        returnRate = BigDecimal("15.45"),
                                    ),
                                ),
                        ),
                    ),
                recommendedProducts =
                    RecommendedProductsResult.Success(
                        listOf(
                            InvestmentProduct(
                                productId = "PROD-001",
                                productType = ProductType.ETF,
                                name = "S&P500 ETF",
                                description = "미국 대형주",
                                expectedReturnRate = BigDecimal("8.5"),
                                riskLevel = RiskLevel.MEDIUM,
                                minimumAmount = BigDecimal("10000"),
                            ),
                        ),
                    ),
            )

        describe("GET /api/v1/investment/dashboard") {
            it("정상 요청 시 200 응답과 대시보드 데이터를 반환한다") {
                val userId = "user-001"
                every { getDashboardUseCase.getDashboard(userId) } returns successDashboard(userId)

                mockMvc
                    .perform(
                        get("/api/v1/investment/dashboard").header("X-User-Id", userId),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.userId").value(userId))
                    .andExpect(jsonPath("$.assetSummary.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.foreignStockPortfolio.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.recommendedProducts.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.assetSummary.savingsList[0].accountId").value("ACC-001"))
                    .andExpect(jsonPath("$.foreignStockPortfolio.holdingList[0].ticker").value("AAPL"))
            }

            it("제휴사 주식 조회 실패 시 해당 섹션이 FAILURE 상태로 포함된다") {
                val userId = "user-002"
                val partialDashboard =
                    successDashboard(userId).copy(
                        foreignStockPortfolio = ForeignStockPortfolioResult.Failure(FailureReason.TIMEOUT),
                    )
                every { getDashboardUseCase.getDashboard(userId) } returns partialDashboard

                mockMvc
                    .perform(
                        get("/api/v1/investment/dashboard").header("X-User-Id", userId),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.assetSummary.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.foreignStockPortfolio.status").value("FAILURE"))
                    .andExpect(jsonPath("$.foreignStockPortfolio.failureReason").value("TIMEOUT"))
                    .andExpect(jsonPath("$.foreignStockPortfolio.holdingList").doesNotExist())
            }

            it("X-User-Id 헤더가 없으면 400 응답을 반환한다") {
                mockMvc
                    .perform(get("/api/v1/investment/dashboard"))
                    .andExpect(status().isBadRequest)
            }

            it("추천 엔진 CB OPEN 상태를 응답에 포함한다") {
                val userId = "user-003"
                every { getDashboardUseCase.getDashboard(userId) } returns
                    successDashboard(userId).copy(
                        recommendedProducts = RecommendedProductsResult.Failure(FailureReason.CIRCUIT_OPEN),
                    )

                mockMvc
                    .perform(
                        get("/api/v1/investment/dashboard").header("X-User-Id", userId),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.recommendedProducts.status").value("FAILURE"))
                    .andExpect(jsonPath("$.recommendedProducts.failureReason").value("CIRCUIT_OPEN"))
            }

            it("totalAssetValueInKrw는 계좌 잔액과 해외 주식 합산이다") {
                val userId = "user-004"
                every { getDashboardUseCase.getDashboard(userId) } returns successDashboard(userId)

                mockMvc
                    .perform(
                        get("/api/v1/investment/dashboard").header("X-User-Id", userId),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.totalAssetValueInKrw").value(21000000))
            }

            it("계좌 조회 실패 시 totalAssetValueInKrw는 해외 주식 금액만 포함한다") {
                val userId = "user-005"
                every { getDashboardUseCase.getDashboard(userId) } returns
                    successDashboard(userId).copy(
                        assetSummary = AssetSummaryResult.Failure(FailureReason.SERVICE_UNAVAILABLE),
                    )

                mockMvc
                    .perform(
                        get("/api/v1/investment/dashboard").header("X-User-Id", userId),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.totalAssetValueInKrw").value(3000000))
            }

            it("generatedAt 필드가 응답에 포함된다") {
                val userId = "user-006"
                every { getDashboardUseCase.getDashboard(userId) } returns successDashboard(userId)

                mockMvc
                    .perform(
                        get("/api/v1/investment/dashboard").header("X-User-Id", userId),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.generatedAt").exists())
            }
        }
    }
}
