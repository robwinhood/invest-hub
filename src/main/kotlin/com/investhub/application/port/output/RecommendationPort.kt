package com.investhub.application.port.output

import com.investhub.domain.product.InvestmentProduct

interface RecommendationPort {
    fun getRecommendedProducts(userId: String): List<InvestmentProduct>
}
