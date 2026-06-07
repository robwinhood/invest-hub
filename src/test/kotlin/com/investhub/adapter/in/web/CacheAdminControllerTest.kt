package com.investhub.adapter.`in`.web

import com.investhub.application.service.CacheInvalidationService
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class CacheAdminControllerTest : DescribeSpec() {
    private val cacheInvalidationService = mockk<CacheInvalidationService>(relaxed = true)
    private val controller = CacheAdminController(cacheInvalidationService)
    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller).build()

    init {
        describe("GET /admin/cache — 캐시 목록 조회") {
            it("등록된 캐시 이름 목록을 반환한다") {
                every { cacheInvalidationService.listCacheNames() } returns listOf("recommendations:v7a0fe702")

                mockMvc
                    .perform(get("/admin/cache"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.caches[0]").value("recommendations:v7a0fe702"))
                    .andExpect(jsonPath("$.count").value(1))
            }
        }

        describe("DELETE /admin/cache/{cacheName}/{key} — 특정 키 무효화") {
            it("evict를 호출하고 200을 반환한다") {
                mockMvc
                    .perform(delete("/admin/cache/recommendations/user-001"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value("evicted"))
                    .andExpect(jsonPath("$.cache").value("recommendations"))
                    .andExpect(jsonPath("$.key").value("user-001"))

                verify(exactly = 1) { cacheInvalidationService.evict("recommendations", "user-001") }
            }
        }

        describe("POST /admin/cache/{cacheName}/{key}/refresh — 무효화 + 재갱신") {
            it("evictAndRefresh를 호출하고 200을 반환한다") {
                mockMvc
                    .perform(post("/admin/cache/recommendations/user-001/refresh"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value("evicted_and_refreshed"))

                verify(exactly = 1) { cacheInvalidationService.evictAndRefresh("recommendations", "user-001") }
            }
        }

        describe("DELETE /admin/cache/{cacheName} — 전체 무효화") {
            it("evictAll을 호출하고 200을 반환한다") {
                mockMvc
                    .perform(delete("/admin/cache/recommendations"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value("evicted_all"))

                verify(exactly = 1) { cacheInvalidationService.evictAll("recommendations") }
            }
        }

        describe("POST /admin/cache/{cacheName}/refresh — 전체 무효화 + 재갱신") {
            it("evictAllAndRefresh를 호출하고 200을 반환한다") {
                mockMvc
                    .perform(post("/admin/cache/recommendations/refresh"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value("evicted_all_and_refreshed"))

                verify(exactly = 1) { cacheInvalidationService.evictAllAndRefresh("recommendations") }
            }
        }
    }
}
