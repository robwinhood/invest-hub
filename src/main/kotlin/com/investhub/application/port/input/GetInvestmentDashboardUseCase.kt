package com.investhub.application.port.input

import com.investhub.domain.dashboard.InvestmentDashboard

interface GetInvestmentDashboardUseCase {
    fun getDashboard(userId: String): InvestmentDashboard
}
