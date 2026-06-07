package com.investhub.adapter.`in`.web

import com.investhub.application.port.input.GetInvestmentDashboardUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.mockk
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class GlobalExceptionHandlerTest : DescribeSpec() {
    private val getDashboardUseCase = mockk<GetInvestmentDashboardUseCase>(relaxed = true)
    private val controller = InvestmentDashboardController(getDashboardUseCase)

    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

    init {
        describe("GlobalExceptionHandler") {
            it("X-User-Id 헤더 누락 시 400과 Problem Details 형식으로 응답한다") {
                mockMvc
                    .perform(get("/api/v1/investment/dashboard"))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.title").value("Required Header Missing"))
                    .andExpect(jsonPath("$.detail").exists())
            }
        }
    }
}
