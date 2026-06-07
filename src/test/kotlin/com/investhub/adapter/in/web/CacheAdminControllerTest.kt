package com.investhub.adapter.`in`.web

import com.investhub.application.service.CacheInfo
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
            it("캐시 이름과 함께 엔트리 키 목록을 반환한다") {
                every { cacheInvalidationService.listCaches() } returns
                    listOf(
                        CacheInfo(
                            name = "recommendations:v7a0fe702",
                            baseName = "recommendations",
                            entryCount = 2,
                            keys = listOf("user-001", "user-002"),
                        ),
                    )

                mockMvc
                    .perform(get("/admin/cache"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.caches[0].name").value("recommendations:v7a0fe702"))
                    .andExpect(jsonPath("$.caches[0].baseName").value("recommendations"))
                    .andExpect(jsonPath("$.caches[0].entryCount").value(2))
                    .andExpect(jsonPath("$.caches[0].keys[0]").value("user-001"))
                    .andExpect(jsonPath("$.caches[0].keys[1]").value("user-002"))
                    .andExpect(jsonPath("$.count").value(1))
            }
        }

        describe("DELETE /admin/cache/{cacheName}/{key} — 특정 키 무효화") {
            it("실제로 제거되면 status=evicted를 반환한다") {
                every { cacheInvalidationService.evict("recommendations", "user-001") } returns true

                mockMvc
                    .perform(delete("/admin/cache/recommendations/user-001"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value("evicted"))
                    .andExpect(jsonPath("$.cache").value("recommendations"))
                    .andExpect(jsonPath("$.key").value("user-001"))

                verify(exactly = 1) { cacheInvalidationService.evict("recommendations", "user-001") }
            }

            it("원래 키가 없었으면 status=not_found를 반환한다") {
                every { cacheInvalidationService.evict("recommendations", "ghost") } returns false

                mockMvc
                    .perform(delete("/admin/cache/recommendations/ghost"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value("not_found"))
                    .andExpect(jsonPath("$.key").value("ghost"))

                verify(exactly = 1) { cacheInvalidationService.evict("recommendations", "ghost") }
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
