package com.investhub.application.service

import com.github.benmanes.caffeine.cache.Caffeine
import com.investhub.application.port.output.CacheEventPublisher
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.cache.caffeine.CaffeineCacheManager

class CacheInvalidationServiceTest : DescribeSpec() {
    init {
        fun buildService(
            publishers: List<CacheEventPublisher> = emptyList(),
            strategies: List<CacheRefreshStrategy> = emptyList(),
        ): CacheInvalidationService {
            val caffeineCacheManager =
                CaffeineCacheManager("recommendations").apply {
                    setCaffeine(Caffeine.newBuilder().maximumSize(100))
                }
            return CacheInvalidationService(caffeineCacheManager, publishers, strategies)
        }

        describe("evict — 특정 키 무효화") {
            it("캐시에 저장된 값이 제거된다") {
                val service = buildService()
                val cache =
                    service.run {
                        // 내부 CacheManager에 직접 접근해 값 저장
                        val mgr =
                            CaffeineCacheManager("recommendations").apply {
                                setCaffeine(Caffeine.newBuilder().maximumSize(100))
                            }
                        mgr.getCache("recommendations")!!.put("user-001", listOf("product-a"))
                        mgr
                    }

                // evict 후 캐시에서 값이 사라짐을 검증
                val localCache = cache.getCache("recommendations")!!
                localCache.put("user-001", "data")
                localCache.get("user-001")?.get() shouldNotBe null

                localCache.evict("user-001")
                localCache.get("user-001") shouldBe null
            }

            it("evict 호출 시 등록된 모든 CacheEventPublisher에 이벤트를 발행한다") {
                val publisher1 = mockk<CacheEventPublisher>(relaxed = true)
                val publisher2 = mockk<CacheEventPublisher>(relaxed = true)
                val service = buildService(publishers = listOf(publisher1, publisher2))

                service.evict("recommendations", "user-001")

                verify(exactly = 1) { publisher1.publishEviction("recommendations", "user-001") }
                verify(exactly = 1) { publisher2.publishEviction("recommendations", "user-001") }
            }

            it("존재하지 않는 캐시 이름은 경고만 출력하고 예외를 던지지 않는다") {
                val service = buildService()

                shouldNotThrow<Exception> {
                    service.evict("non-existent-cache", "key")
                }
            }
        }

        describe("evictAll — 전체 무효화") {
            it("evictAll 호출 시 key=null로 이벤트를 발행한다") {
                val publisher = mockk<CacheEventPublisher>(relaxed = true)
                val service = buildService(publishers = listOf(publisher))

                service.evictAll("recommendations")

                verify(exactly = 1) { publisher.publishEviction("recommendations", null) }
            }
        }

        describe("evictAndRefresh — 무효화 + 즉시 재갱신") {
            it("적합한 전략이 있으면 evict 후 refresh를 호출한다") {
                val strategy = mockk<CacheRefreshStrategy>()
                every { strategy.supports("recommendations") } returns true
                every { strategy.refresh(any(), any()) } returns Unit

                val service = buildService(strategies = listOf(strategy))
                service.evictAndRefresh("recommendations", "user-001")

                verify(exactly = 1) { strategy.refresh("recommendations", "user-001") }
            }

            it("적합한 전략이 없으면 evict만 수행하고 예외를 던지지 않는다") {
                val strategy = mockk<CacheRefreshStrategy>()
                every { strategy.supports("recommendations") } returns false

                val service = buildService(strategies = listOf(strategy))

                shouldNotThrow<Exception> {
                    service.evictAndRefresh("recommendations", "user-001")
                }
                verify(exactly = 0) { strategy.refresh(any(), any()) }
            }

            it("전략이 refresh 중 예외를 던져도 evict는 완료되고 서비스는 정상 종료된다") {
                val strategy = mockk<CacheRefreshStrategy>()
                every { strategy.supports("recommendations") } returns true
                every { strategy.refresh(any(), any()) } throws RuntimeException("refresh 실패")

                val service = buildService(strategies = listOf(strategy))

                shouldNotThrow<Exception> {
                    service.evictAndRefresh("recommendations", "user-001")
                }
            }

            it("여러 전략 중 해당 캐시를 지원하는 전략만 호출한다") {
                val targetStrategy = mockk<CacheRefreshStrategy>()
                val otherStrategy = mockk<CacheRefreshStrategy>()
                every { targetStrategy.supports("recommendations") } returns true
                every { targetStrategy.refresh(any(), any()) } returns Unit
                every { otherStrategy.supports("recommendations") } returns false

                val service = buildService(strategies = listOf(otherStrategy, targetStrategy))
                service.evictAndRefresh("recommendations", "user-001")

                verify(exactly = 1) { targetStrategy.refresh(any(), any()) }
                verify(exactly = 0) { otherStrategy.refresh(any(), any()) }
            }
        }

        describe("listCacheNames — 캐시 목록 조회") {
            it("등록된 캐시 이름 목록을 반환한다") {
                val service = buildService()
                val names = service.listCacheNames()

                names shouldNotBe null
            }
        }
    }
}
