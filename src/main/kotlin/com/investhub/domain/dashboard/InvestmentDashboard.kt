package com.investhub.domain.dashboard

import com.investhub.domain.account.AssetSummary
import com.investhub.domain.product.InvestmentProduct
import com.investhub.domain.stock.ForeignStockPortfolio
import java.time.LocalDateTime

data class InvestmentDashboard(
    val userId: String,
    val generatedAt: LocalDateTime = LocalDateTime.now(),
    val assetSummary: AssetSummaryResult,
    val foreignStockPortfolio: ForeignStockPortfolioResult,
    val recommendedProducts: RecommendedProductsResult,
)

sealed class AssetSummaryResult {
    data class Success(
        val data: AssetSummary,
    ) : AssetSummaryResult()

    data class Failure(
        val reason: FailureReason,
    ) : AssetSummaryResult()
}

sealed class ForeignStockPortfolioResult {
    data class Success(
        val data: ForeignStockPortfolio,
    ) : ForeignStockPortfolioResult()

    data class Failure(
        val reason: FailureReason,
    ) : ForeignStockPortfolioResult()
}

sealed class RecommendedProductsResult {
    data class Success(
        val productList: List<InvestmentProduct>,
    ) : RecommendedProductsResult()

    data class Failure(
        val reason: FailureReason,
    ) : RecommendedProductsResult()
}

enum class FailureReason {
    CIRCUIT_OPEN,
    TIMEOUT,
    RESOURCE_EXHAUSTED,
    SERVICE_UNAVAILABLE,
}
