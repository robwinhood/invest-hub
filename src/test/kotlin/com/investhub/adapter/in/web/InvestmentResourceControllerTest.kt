package com.investhub.adapter.`in`.web

import com.investhub.application.port.input.GetAssetSummaryUseCase
import com.investhub.application.port.input.GetForeignStockPortfolioUseCase
import com.investhub.application.port.input.GetRecommendedProductsUseCase
import com.investhub.domain.account.AccountType
import com.investhub.domain.account.AssetSummary
import com.investhub.domain.account.SavingsAccount
import com.investhub.domain.dashboard.AssetSummaryResult
import com.investhub.domain.dashboard.FailureReason
import com.investhub.domain.dashboard.ForeignStockPortfolioResult
import com.investhub.domain.dashboard.RecommendedProductsResult
import com.investhub.domain.product.InvestmentProduct
import com.investhub.domain.product.ProductType
import com.investhub.domain.product.RiskLevel
import com.investhub.domain.stock.ForeignStockHolding
import com.investhub.domain.stock.ForeignStockPortfolio
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import org.hamcrest.Matchers.containsString
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal

/**
 * 도메인별 단독 리소스 엔드포인트 검증.
 *
 * - 성공: 200 + **데이터 속성에 맞는 Cache-Control** (자산=private/30s, 주식=no-store, 추천=private/300s)
 * - 실패: 부분 실패 원인(FailureReason)에 맞는 HTTP 상태 (503/504/429)
 */
class InvestmentResourceControllerTest :
    DescribeSpec({
        val assetUseCase = mockk<GetAssetSummaryUseCase>()
        val stockUseCase = mockk<GetForeignStockPortfolioUseCase>()
        val recoUseCase = mockk<GetRecommendedProductsUseCase>()

        val mockMvc =
            MockMvcBuilders
                .standaloneSetup(
                    AssetController(assetUseCase),
                    ForeignStockController(stockUseCase),
                    RecommendationController(recoUseCase),
                ).build()

        val sampleAsset =
            AssetSummary(
                totalBalance = BigDecimal("5000000"),
                savingsList =
                    listOf(
                        SavingsAccount("ACC-001", AccountType.SAVINGS, "보통예금", BigDecimal("5000000")),
                    ),
                investmentList = emptyList(),
            )
        val sampleStock =
            ForeignStockPortfolio(
                totalValueInKrw = BigDecimal("1270000"),
                holdingList =
                    listOf(
                        ForeignStockHolding(
                            "AAPL",
                            "Apple Inc.",
                            BigDecimal("5"),
                            BigDecimal("189.50"),
                            "USD",
                            BigDecimal("1270000"),
                            BigDecimal("1100000"),
                            BigDecimal("15.45"),
                        ),
                    ),
            )
        val sampleProducts =
            listOf(
                InvestmentProduct(
                    "PROD-001",
                    ProductType.ETF,
                    "S&P500 ETF",
                    "미국 대형주",
                    BigDecimal("8.5"),
                    RiskLevel.MEDIUM,
                    BigDecimal("10000"),
                ),
            )

        describe("GET /api/v1/investment/assets") {
            it("성공 시 200 + 자산 데이터 + private/30s Cache-Control") {
                every { assetUseCase.getAssetSummary("u1") } returns AssetSummaryResult.Success(sampleAsset)

                mockMvc
                    .perform(get("/api/v1/investment/assets").header("X-User-Id", "u1"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.savingsList[0].accountId").value("ACC-001"))
                    .andExpect(header().string("Cache-Control", containsString("max-age=30")))
                    .andExpect(header().string("Cache-Control", containsString("private")))
            }

            it("CB OPEN이면 503 + FAILURE") {
                every { assetUseCase.getAssetSummary("u2") } returns
                    AssetSummaryResult.Failure(FailureReason.CIRCUIT_OPEN)

                mockMvc
                    .perform(get("/api/v1/investment/assets").header("X-User-Id", "u2"))
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(jsonPath("$.status").value("FAILURE"))
                    .andExpect(jsonPath("$.failureReason").value("CIRCUIT_OPEN"))
            }

            it("X-User-Id 헤더가 없으면 400") {
                mockMvc
                    .perform(get("/api/v1/investment/assets"))
                    .andExpect(status().isBadRequest)
            }
        }

        describe("GET /api/v1/investment/foreign-stocks") {
            it("성공 시 200 + 실시간이라 no-store Cache-Control") {
                every { stockUseCase.getForeignStockPortfolio("u1") } returns
                    ForeignStockPortfolioResult.Success(sampleStock)

                mockMvc
                    .perform(get("/api/v1/investment/foreign-stocks").header("X-User-Id", "u1"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.holdingList[0].ticker").value("AAPL"))
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
            }

            it("타임아웃이면 504") {
                every { stockUseCase.getForeignStockPortfolio("u2") } returns
                    ForeignStockPortfolioResult.Failure(FailureReason.TIMEOUT)

                mockMvc
                    .perform(get("/api/v1/investment/foreign-stocks").header("X-User-Id", "u2"))
                    .andExpect(status().isGatewayTimeout)
                    .andExpect(jsonPath("$.failureReason").value("TIMEOUT"))
            }
        }

        describe("GET /api/v1/investment/recommendations") {
            it("성공 시 200 + private/300s Cache-Control (L2 TTL과 정렬)") {
                every { recoUseCase.getRecommendedProducts("u1") } returns
                    RecommendedProductsResult.Success(sampleProducts)

                mockMvc
                    .perform(get("/api/v1/investment/recommendations").header("X-User-Id", "u1"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.productList[0].productId").value("PROD-001"))
                    .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                    .andExpect(header().string("Cache-Control", containsString("private")))
            }

            it("Bulkhead 포화면 429") {
                every { recoUseCase.getRecommendedProducts("u2") } returns
                    RecommendedProductsResult.Failure(FailureReason.RESOURCE_EXHAUSTED)

                mockMvc
                    .perform(get("/api/v1/investment/recommendations").header("X-User-Id", "u2"))
                    .andExpect(status().isTooManyRequests)
                    .andExpect(jsonPath("$.failureReason").value("RESOURCE_EXHAUSTED"))
            }
        }
    })
