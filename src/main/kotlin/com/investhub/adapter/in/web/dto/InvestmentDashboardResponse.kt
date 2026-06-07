package com.investhub.adapter.`in`.web.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.investhub.domain.dashboard.AssetSummaryResult
import com.investhub.domain.dashboard.ForeignStockPortfolioResult
import com.investhub.domain.dashboard.InvestmentDashboard
import com.investhub.domain.dashboard.RecommendedProductsResult
import java.math.BigDecimal
import java.time.LocalDateTime

enum class SectionStatus { SUCCESS, FAILURE }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InvestmentDashboardResponse(
    val userId: String,
    val generatedAt: LocalDateTime,
    val totalAssetValueInKrw: BigDecimal,
    val assetSummary: AssetSectionResponse,
    val foreignStockPortfolio: ForeignStockSectionResponse,
    val recommendedProducts: RecommendationSectionResponse,
) {
    companion object {
        fun from(dashboard: InvestmentDashboard): InvestmentDashboardResponse {
            val assetSection = AssetSectionResponse.from(dashboard.assetSummary)
            val stockSection = ForeignStockSectionResponse.from(dashboard.foreignStockPortfolio)
            val recommendSection = RecommendationSectionResponse.from(dashboard.recommendedProducts)

            val totalAsset =
                (assetSection.totalBalance ?: BigDecimal.ZERO) +
                    (stockSection.totalValueInKrw ?: BigDecimal.ZERO)

            return InvestmentDashboardResponse(
                userId = dashboard.userId,
                generatedAt = dashboard.generatedAt,
                totalAssetValueInKrw = totalAsset,
                assetSummary = assetSection,
                foreignStockPortfolio = stockSection,
                recommendedProducts = recommendSection,
            )
        }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AssetSectionResponse(
    val status: SectionStatus,
    val failureReason: String?,
    val totalBalance: BigDecimal?,
    val savingsList: List<SavingsAccountResponse>?,
    val investmentList: List<InvestmentHoldingResponse>?,
) {
    companion object {
        fun from(result: AssetSummaryResult): AssetSectionResponse =
            when (result) {
                is AssetSummaryResult.Success ->
                    AssetSectionResponse(
                        status = SectionStatus.SUCCESS,
                        failureReason = null,
                        totalBalance = result.data.totalBalance,
                        savingsList = result.data.savingsList.map { SavingsAccountResponse.from(it) },
                        investmentList = result.data.investmentList.map { InvestmentHoldingResponse.from(it) },
                    )
                is AssetSummaryResult.Failure ->
                    AssetSectionResponse(
                        status = SectionStatus.FAILURE,
                        failureReason = result.reason.name,
                        totalBalance = null,
                        savingsList = null,
                        investmentList = null,
                    )
            }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ForeignStockSectionResponse(
    val status: SectionStatus,
    val failureReason: String?,
    val totalValueInKrw: BigDecimal?,
    val holdingList: List<ForeignStockHoldingResponse>?,
) {
    companion object {
        fun from(result: ForeignStockPortfolioResult): ForeignStockSectionResponse =
            when (result) {
                is ForeignStockPortfolioResult.Success ->
                    ForeignStockSectionResponse(
                        status = SectionStatus.SUCCESS,
                        failureReason = null,
                        totalValueInKrw = result.data.totalValueInKrw,
                        holdingList = result.data.holdingList.map { ForeignStockHoldingResponse.from(it) },
                    )
                is ForeignStockPortfolioResult.Failure ->
                    ForeignStockSectionResponse(
                        status = SectionStatus.FAILURE,
                        failureReason = result.reason.name,
                        totalValueInKrw = null,
                        holdingList = null,
                    )
            }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RecommendationSectionResponse(
    val status: SectionStatus,
    val failureReason: String?,
    val productList: List<InvestmentProductResponse>?,
) {
    companion object {
        fun from(result: RecommendedProductsResult): RecommendationSectionResponse =
            when (result) {
                is RecommendedProductsResult.Success ->
                    RecommendationSectionResponse(
                        status = SectionStatus.SUCCESS,
                        failureReason = null,
                        productList = result.productList.map { InvestmentProductResponse.from(it) },
                    )
                is RecommendedProductsResult.Failure ->
                    RecommendationSectionResponse(
                        status = SectionStatus.FAILURE,
                        failureReason = result.reason.name,
                        productList = null,
                    )
            }
    }
}

data class SavingsAccountResponse(
    val accountId: String,
    val accountType: String,
    val accountTypeLabel: String,
    val productName: String,
    val balance: BigDecimal,
    val currency: String,
) {
    companion object {
        fun from(account: com.investhub.domain.account.SavingsAccount) =
            SavingsAccountResponse(
                accountId = account.accountId,
                accountType = account.accountType.name,
                accountTypeLabel = account.accountType.label,
                productName = account.productName,
                balance = account.balance,
                currency = account.currency,
            )
    }
}

data class InvestmentHoldingResponse(
    val holdingId: String,
    val holdingType: String,
    val holdingTypeLabel: String,
    val name: String,
    val currentValue: BigDecimal,
    val purchaseValue: BigDecimal,
    val returnRate: BigDecimal,
    val currency: String,
) {
    companion object {
        fun from(holding: com.investhub.domain.account.InvestmentHolding) =
            InvestmentHoldingResponse(
                holdingId = holding.holdingId,
                holdingType = holding.holdingType.name,
                holdingTypeLabel = holding.holdingType.label,
                name = holding.name,
                currentValue = holding.currentValue,
                purchaseValue = holding.purchaseValue,
                returnRate = holding.returnRate,
                currency = holding.currency,
            )
    }
}

data class ForeignStockHoldingResponse(
    val ticker: String,
    val stockName: String,
    val quantity: BigDecimal,
    val currentPrice: BigDecimal,
    val currency: String,
    val currentValueInKrw: BigDecimal,
    val purchaseValueInKrw: BigDecimal,
    val returnRate: BigDecimal,
) {
    companion object {
        fun from(holding: com.investhub.domain.stock.ForeignStockHolding) =
            ForeignStockHoldingResponse(
                ticker = holding.ticker,
                stockName = holding.stockName,
                quantity = holding.quantity,
                currentPrice = holding.currentPrice,
                currency = holding.currency,
                currentValueInKrw = holding.currentValueInKrw,
                purchaseValueInKrw = holding.purchaseValueInKrw,
                returnRate = holding.returnRate,
            )
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InvestmentProductResponse(
    val productId: String,
    val productType: String,
    val productTypeLabel: String,
    val name: String,
    val description: String,
    val expectedReturnRate: BigDecimal?,
    val riskLevel: String,
    val riskLevelLabel: String,
    val minimumAmount: BigDecimal,
) {
    companion object {
        fun from(product: com.investhub.domain.product.InvestmentProduct) =
            InvestmentProductResponse(
                productId = product.productId,
                productType = product.productType.name,
                productTypeLabel = product.productType.label,
                name = product.name,
                description = product.description,
                expectedReturnRate = product.expectedReturnRate,
                riskLevel = product.riskLevel.name,
                riskLevelLabel = product.riskLevel.label,
                minimumAmount = product.minimumAmount,
            )
    }
}
