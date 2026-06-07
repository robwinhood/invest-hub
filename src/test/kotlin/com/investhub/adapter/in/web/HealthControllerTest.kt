package com.investhub.adapter.`in`.web

import com.investhub.adapter.`in`.lifecycle.StartupReadyTracker
import com.investhub.application.service.WarmupService
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class HealthControllerTest : DescribeSpec() {
    private val warmupService = mockk<WarmupService>(relaxed = true)
    private val tracker = mockk<StartupReadyTracker>(relaxed = true)
    private val controller = HealthController(warmupService, tracker)
    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller).build()

    init {
        beforeEach {
            clearMocks(warmupService, tracker)
        }

        describe("GET /health/startup — Startup Probe") {
            it("웜업이 완료된 경우 200 OK를 반환한다") {
                every { warmupService.isCompleted } returns true

                mockMvc
                    .perform(get("/health/startup"))
                    .andExpect(status().isOk)
                    .andExpect(content().string("OK"))
            }

            it("웜업이 진행 중인 경우 503을 반환한다 (블로킹 없이 즉시)") {
                every { warmupService.isCompleted } returns false
                every { warmupService.isRunning } returns true

                mockMvc
                    .perform(get("/health/startup"))
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(content().string("warming up"))
            }

            it("웜업이 아직 시작 안 된 경우 동기 실행 후 200 반환한다") {
                every { warmupService.isCompleted } returns false
                every { warmupService.isRunning } returns false

                mockMvc
                    .perform(get("/health/startup"))
                    .andExpect(status().isOk)

                verify(exactly = 1) { warmupService.warmUp() }
            }
        }

        describe("GET /health/ready — Readiness Probe") {
            it("웜업이 완료된 경우 200 OK와 함께 probe gap을 기록한다") {
                every { warmupService.isCompleted } returns true

                mockMvc
                    .perform(get("/health/ready"))
                    .andExpect(status().isOk)
                    .andExpect(content().string("OK"))

                verify(exactly = 1) { tracker.recordFirstProbe() }
            }

            it("웜업이 미완료인 경우 503을 반환한다") {
                every { warmupService.isCompleted } returns false

                mockMvc
                    .perform(get("/health/ready"))
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(content().string("not ready"))

                verify(exactly = 0) { tracker.recordFirstProbe() }
            }
        }

        describe("GET /health/live — Liveness Probe") {
            it("항상 200 OK를 반환한다") {
                mockMvc
                    .perform(get("/health/live"))
                    .andExpect(status().isOk)
                    .andExpect(content().string("OK"))
            }

            it("웜업 상태와 무관하게 200 OK를 반환한다") {
                every { warmupService.isCompleted } returns false
                every { warmupService.isRunning } returns true

                mockMvc
                    .perform(get("/health/live"))
                    .andExpect(status().isOk)
            }
        }
    }
}
