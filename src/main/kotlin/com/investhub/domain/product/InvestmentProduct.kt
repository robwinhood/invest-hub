package com.investhub.domain.product

import java.math.BigDecimal

data class InvestmentProduct(
    val productId: String,
    val productType: ProductType,
    val name: String,
    val description: String,
    val expectedReturnRate: BigDecimal?,
    val riskLevel: RiskLevel,
    val minimumAmount: BigDecimal,
) {
    init {
        require(productId.isNotBlank()) { "상품 ID는 비어있을 수 없습니다" }
        require(name.isNotBlank()) { "상품명은 비어있을 수 없습니다" }
        require(minimumAmount > BigDecimal.ZERO) { "최소 투자금액은 0보다 커야 합니다: $minimumAmount" }
    }
}

enum class ProductType(
    val label: String,
) {
    FUND("펀드"),
    BOND("채권"),
    ETF("ETF"),
    DOMESTIC_STOCK("국내 주식"),
    FOREIGN_STOCK("해외 주식"),
}

enum class RiskLevel(
    val label: String,
    val level: Int,
) {
    VERY_LOW("매우 낮음", 1),
    LOW("낮음", 2),
    MEDIUM("보통", 3),
    HIGH("높음", 4),
    VERY_HIGH("매우 높음", 5),
}
