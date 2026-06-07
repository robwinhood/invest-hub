package com.investhub.application.service

import com.investhub.application.port.output.AccountPort
import com.investhub.application.port.output.ForeignStockPort
import com.investhub.application.port.output.RecommendationPort
import com.investhub.domain.account.AccountType
import com.investhub.domain.account.AssetSummary
import com.investhub.domain.account.HoldingType
import com.investhub.domain.account.InvestmentHolding
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
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class InvestmentDashboardServiceTest : DescribeSpec() {
    init {
        val accountPort = mockk<AccountPort>()
        val foreignStockPort = mockk<ForeignStockPort>()
        val recommendationPort = mockk<RecommendationPort>()
        val executor = Executors.newVirtualThreadPerTaskExecutor()

        val service =
            InvestmentDashboardService(
                accountPort = accountPort,
                foreignStockPort = foreignStockPort,
                recommendationPort = recommendationPort,
                executor = executor,
            )

        fun mockAssetSummary() =
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
            )

        fun mockPortfolio() =
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
            )

        fun mockProducts() =
            listOf(
                InvestmentProduct(
                    productId = "PROD-001",
                    productType = ProductType.ETF,
                    name = "S&P500 ETF",
                    description = "미국 대형주 ETF",
                    expectedReturnRate = BigDecimal("8.5"),
                    riskLevel = RiskLevel.MEDIUM,
                    minimumAmount = BigDecimal("10000"),
                ),
            )

        describe("getDashboard — 정상 경로") {
            it("세 데이터 소스가 모두 정상일 때 SUCCESS 결과를 반환한다") {
                every { accountPort.getAssetSummary(any()) } returns mockAssetSummary()
                every { foreignStockPort.getPortfolio(any()) } returns mockPortfolio()
                every { recommendationPort.getRecommendedProducts(any()) } returns mockProducts()

                val result = service.getDashboard("user-001")

                result.userId shouldBe "user-001"
                result.assetSummary.shouldBeInstanceOf<AssetSummaryResult.Success>()
                result.foreignStockPortfolio.shouldBeInstanceOf<ForeignStockPortfolioResult.Success>()
                result.recommendedProducts.shouldBeInstanceOf<RecommendedProductsResult.Success>()
            }

            it("generatedAt이 null이 아니다") {
                every { accountPort.getAssetSummary(any()) } returns mockAssetSummary()
                every { foreignStockPort.getPortfolio(any()) } returns mockPortfolio()
                every { recommendationPort.getRecommendedProducts(any()) } returns mockProducts()

                val result = service.getDashboard("user-001")

                result.generatedAt shouldNotBe null
            }

            it("요청한 userId가 결과에 그대로 반영된다") {
                every { accountPort.getAssetSummary("user-999") } returns mockAssetSummary()
                every { foreignStockPort.getPortfolio("user-999") } returns mockPortfolio()
                every { recommendationPort.getRecommendedProducts("user-999") } returns mockProducts()

                val result = service.getDashboard("user-999")

                result.userId shouldBe "user-999"
                verify(exactly = 1) { accountPort.getAssetSummary("user-999") }
                verify(exactly = 1) { foreignStockPort.getPortfolio("user-999") }
                verify(exactly = 1) { recommendationPort.getRecommendedProducts("user-999") }
            }
        }

        describe("getDashboard — 부분 실패") {
            it("제휴사 주식 조회 실패 시 해당 섹션만 FAILURE이고 나머지는 SUCCESS다") {
                every { accountPort.getAssetSummary(any()) } returns mockAssetSummary()
                every { foreignStockPort.getPortfolio(any()) } throws RuntimeException("제휴사 오류")
                every { recommendationPort.getRecommendedProducts(any()) } returns mockProducts()

                val result = service.getDashboard("user-001")

                result.assetSummary.shouldBeInstanceOf<AssetSummaryResult.Success>()
                result.foreignStockPortfolio.shouldBeInstanceOf<ForeignStockPortfolioResult.Failure>()
                result.recommendedProducts.shouldBeInstanceOf<RecommendedProductsResult.Success>()
            }

            it("계좌 조회 실패 시에도 해외 주식과 추천 상품은 SUCCESS다") {
                every { accountPort.getAssetSummary(any()) } throws RuntimeException("원장 오류")
                every { foreignStockPort.getPortfolio(any()) } returns mockPortfolio()
                every { recommendationPort.getRecommendedProducts(any()) } returns mockProducts()

                val result = service.getDashboard("user-001")

                result.assetSummary.shouldBeInstanceOf<AssetSummaryResult.Failure>()
                result.foreignStockPortfolio.shouldBeInstanceOf<ForeignStockPortfolioResult.Success>()
                result.recommendedProducts.shouldBeInstanceOf<RecommendedProductsResult.Success>()
            }

            it("추천 엔진 실패 시 FAILURE 이유를 SERVICE_UNAVAILABLE로 분류한다") {
                every { accountPort.getAssetSummary(any()) } returns mockAssetSummary()
                every { foreignStockPort.getPortfolio(any()) } returns mockPortfolio()
                every { recommendationPort.getRecommendedProducts(any()) } throws RuntimeException("추천 엔진 오류")

                val result = service.getDashboard("user-001")

                val failure = result.recommendedProducts as RecommendedProductsResult.Failure
                failure.reason shouldBe FailureReason.SERVICE_UNAVAILABLE
            }

            it("세 데이터 소스가 모두 실패해도 예외를 던지지 않는다") {
                every { accountPort.getAssetSummary(any()) } throws RuntimeException("계좌 오류")
                every { foreignStockPort.getPortfolio(any()) } throws RuntimeException("주식 오류")
                every { recommendationPort.getRecommendedProducts(any()) } throws RuntimeException("추천 오류")

                shouldNotThrow<Exception> {
                    service.getDashboard("user-001")
                }
            }
        }

        describe("getDashboard — 예외 분류") {
            it("서킷 브레이커 예외는 CIRCUIT_OPEN으로 분류한다") {
                val mockCb = mockk<CircuitBreaker>(relaxed = true)
                every { mockCb.name } returns "account"
                val cbException = CallNotPermittedException.createCallNotPermittedException(mockCb)

                every { accountPort.getAssetSummary(any()) } throws cbException
                every { foreignStockPort.getPortfolio(any()) } returns mockPortfolio()
                every { recommendationPort.getRecommendedProducts(any()) } returns mockProducts()

                val result = service.getDashboard("user-001")

                val failure = result.assetSummary as AssetSummaryResult.Failure
                failure.reason shouldBe FailureReason.CIRCUIT_OPEN
            }

            it("TimeoutException은 TIMEOUT으로 분류한다") {
                every { accountPort.getAssetSummary(any()) } throws java.util.concurrent.TimeoutException("timeout")
                every { foreignStockPort.getPortfolio(any()) } returns mockPortfolio()
                every { recommendationPort.getRecommendedProducts(any()) } returns mockProducts()

                val result = service.getDashboard("user-001")

                val failure = result.assetSummary as AssetSummaryResult.Failure
                failure.reason shouldBe FailureReason.TIMEOUT
            }

            it("BulkheadFullException은 RESOURCE_EXHAUSTED로 분류한다") {
                every { accountPort.getAssetSummary(any()) } throws
                    io.github.resilience4j.bulkhead.BulkheadFullException.createBulkheadFullException(
                        io.github.resilience4j.bulkhead.Bulkhead
                            .ofDefaults("test"),
                    )
                every { foreignStockPort.getPortfolio(any()) } returns mockPortfolio()
                every { recommendationPort.getRecommendedProducts(any()) } returns mockProducts()

                val result = service.getDashboard("user-001")

                val failure = result.assetSummary as AssetSummaryResult.Failure
                failure.reason shouldBe FailureReason.RESOURCE_EXHAUSTED
            }
        }

        describe("getDashboard — 병렬 실행") {
            it("세 포트를 각자 다른 가상 스레드에서 호출한다") {
                val callerThreadIds = ConcurrentHashMap.newKeySet<Long>()

                val trackingAccountPort =
                    object : AccountPort {
                        override fun getAssetSummary(userId: String): AssetSummary {
                            callerThreadIds.add(Thread.currentThread().threadId())
                            Thread.sleep(50)
                            return mockAssetSummary()
                        }
                    }
                val trackingStockPort =
                    object : ForeignStockPort {
                        override fun getPortfolio(userId: String): ForeignStockPortfolio {
                            callerThreadIds.add(Thread.currentThread().threadId())
                            Thread.sleep(50)
                            return mockPortfolio()
                        }
                    }
                val trackingRecommendPort =
                    object : RecommendationPort {
                        override fun getRecommendedProducts(userId: String): List<InvestmentProduct> {
                            callerThreadIds.add(Thread.currentThread().threadId())
                            Thread.sleep(50)
                            return mockProducts()
                        }
                    }

                val parallelService =
                    InvestmentDashboardService(
                        accountPort = trackingAccountPort,
                        foreignStockPort = trackingStockPort,
                        recommendationPort = trackingRecommendPort,
                        executor = Executors.newVirtualThreadPerTaskExecutor(),
                    )

                parallelService.getDashboard("user-001")

                // 세 포트가 각각 다른 가상 스레드에서 호출되었음을 검증
                callerThreadIds.size shouldBe 3
            }

            it("병렬 실행 시 총 소요시간이 순차 합산보다 짧다") {
                val delay = 100L

                val slowAccountPort =
                    object : AccountPort {
                        override fun getAssetSummary(userId: String): AssetSummary {
                            Thread.sleep(delay)
                            return mockAssetSummary()
                        }
                    }
                val slowStockPort =
                    object : ForeignStockPort {
                        override fun getPortfolio(userId: String): ForeignStockPortfolio {
                            Thread.sleep(delay)
                            return mockPortfolio()
                        }
                    }
                val slowRecommendPort =
                    object : RecommendationPort {
                        override fun getRecommendedProducts(userId: String): List<InvestmentProduct> {
                            Thread.sleep(delay)
                            return mockProducts()
                        }
                    }

                val parallelService =
                    InvestmentDashboardService(
                        accountPort = slowAccountPort,
                        foreignStockPort = slowStockPort,
                        recommendationPort = slowRecommendPort,
                        executor = Executors.newVirtualThreadPerTaskExecutor(),
                    )

                val start = System.currentTimeMillis()
                parallelService.getDashboard("user-001")
                val elapsed = System.currentTimeMillis() - start

                // 순차 실행이라면 300ms 이상 소요. 병렬이면 ~100ms
                elapsed shouldBe elapsed.coerceAtMost(delay * 2 + 50)
            }
        }

        describe("도메인 모델 — 검증") {
            it("음수 잔액으로 SavingsAccount를 생성하면 IllegalArgumentException이 발생한다") {
                shouldThrow<IllegalArgumentException> {
                    SavingsAccount(
                        accountId = "ACC-001",
                        accountType = AccountType.SAVINGS,
                        productName = "보통예금",
                        balance = BigDecimal("-1000"),
                    )
                }
            }

            it("빈 accountId로 SavingsAccount를 생성하면 IllegalArgumentException이 발생한다") {
                shouldThrow<IllegalArgumentException> {
                    SavingsAccount(
                        accountId = "",
                        accountType = AccountType.SAVINGS,
                        productName = "보통예금",
                        balance = BigDecimal("1000"),
                    )
                }
            }

            it("수량이 0인 ForeignStockHolding을 생성하면 IllegalArgumentException이 발생한다") {
                shouldThrow<IllegalArgumentException> {
                    ForeignStockHolding(
                        ticker = "AAPL",
                        stockName = "Apple",
                        quantity = BigDecimal.ZERO,
                        currentPrice = BigDecimal("100"),
                        currency = "USD",
                        currentValueInKrw = BigDecimal("100000"),
                        purchaseValueInKrw = BigDecimal("90000"),
                        returnRate = BigDecimal("11.1"),
                    )
                }
            }

            it("minimumAmount가 0인 InvestmentProduct를 생성하면 IllegalArgumentException이 발생한다") {
                shouldThrow<IllegalArgumentException> {
                    InvestmentProduct(
                        productId = "PROD-X",
                        productType = ProductType.ETF,
                        name = "테스트 ETF",
                        description = "테스트",
                        expectedReturnRate = null,
                        riskLevel = RiskLevel.LOW,
                        minimumAmount = BigDecimal.ZERO,
                    )
                }
            }
        }
    }
}
