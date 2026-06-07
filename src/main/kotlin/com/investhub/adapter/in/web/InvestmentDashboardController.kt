package com.investhub.adapter.`in`.web

import com.investhub.adapter.`in`.web.dto.InvestmentDashboardResponse
import com.investhub.application.port.input.GetInvestmentDashboardUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/investment")
class InvestmentDashboardController(
    private val getDashboardUseCase: GetInvestmentDashboardUseCase,
) {
    @GetMapping("/dashboard")
    fun getDashboard(
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<InvestmentDashboardResponse> {
        val dashboard = getDashboardUseCase.getDashboard(userId)
        return ResponseEntity.ok(InvestmentDashboardResponse.from(dashboard))
    }
}
