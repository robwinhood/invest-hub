package com.investhub.domain.account

import java.math.BigDecimal

data class AssetSummary(
    val totalBalance: BigDecimal,
    val savingsList: List<SavingsAccount>,
    val investmentList: List<InvestmentHolding>,
) {
    init {
        require(totalBalance >= BigDecimal.ZERO) { "총 잔액은 0 이상이어야 합니다: $totalBalance" }
    }
}

data class SavingsAccount(
    val accountId: String,
    val accountType: AccountType,
    val productName: String,
    val balance: BigDecimal,
    val currency: String = "KRW",
) {
    init {
        require(accountId.isNotBlank()) { "계좌 ID는 비어있을 수 없습니다" }
        require(balance >= BigDecimal.ZERO) { "잔액은 0 이상이어야 합니다: $balance" }
    }
}

data class InvestmentHolding(
    val holdingId: String,
    val holdingType: HoldingType,
    val name: String,
    val currentValue: BigDecimal,
    val purchaseValue: BigDecimal,
    val returnRate: BigDecimal,
    val currency: String = "KRW",
) {
    init {
        require(holdingId.isNotBlank()) { "보유 ID는 비어있을 수 없습니다" }
        require(currentValue >= BigDecimal.ZERO) { "현재 평가액은 0 이상이어야 합니다: $currentValue" }
        require(purchaseValue >= BigDecimal.ZERO) { "매수 금액은 0 이상이어야 합니다: $purchaseValue" }
    }
}

enum class AccountType(
    val label: String,
) {
    SAVINGS("보통예금"),
    TIME_DEPOSIT("정기예금"),
}

enum class HoldingType(
    val label: String,
) {
    FUND("펀드"),
    BOND("채권"),
}
