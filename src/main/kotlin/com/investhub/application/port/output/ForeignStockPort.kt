package com.investhub.application.port.output

import com.investhub.domain.stock.ForeignStockPortfolio

interface ForeignStockPort {
    fun getPortfolio(userId: String): ForeignStockPortfolio
}
