package com.investhub.domain.stock

import java.math.BigDecimal

data class ForeignStockPortfolio(
    val totalValueInKrw: BigDecimal,
    val holdingList: List<ForeignStockHolding>,
) {
    init {
        require(totalValueInKrw >= BigDecimal.ZERO) { "총 평가액은 0 이상이어야 합니다: $totalValueInKrw" }
    }
}

data class ForeignStockHolding(
    val ticker: String,
    val stockName: String,
    val quantity: BigDecimal,
    val currentPrice: BigDecimal,
    val currency: String,
    val currentValueInKrw: BigDecimal,
    val purchaseValueInKrw: BigDecimal,
    val returnRate: BigDecimal,
) {
    init {
        require(ticker.isNotBlank()) { "종목 코드는 비어있을 수 없습니다" }
        require(quantity > BigDecimal.ZERO) { "수량은 0보다 커야 합니다: $quantity" }
        require(currentPrice >= BigDecimal.ZERO) { "현재가는 0 이상이어야 합니다: $currentPrice" }
        require(currentValueInKrw >= BigDecimal.ZERO) { "현재 평가액(원화)은 0 이상이어야 합니다: $currentValueInKrw" }
        require(purchaseValueInKrw >= BigDecimal.ZERO) { "매수 금액(원화)은 0 이상이어야 합니다: $purchaseValueInKrw" }
    }
}
