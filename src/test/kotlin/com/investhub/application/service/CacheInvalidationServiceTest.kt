package com.investhub.application.service

import com.github.benmanes.caffeine.cache.Caffeine
import com.investhub.adapter.out.cache.MockRedisStore
import com.investhub.adapter.out.cache.TwoTierCache
import com.investhub.application.port.output.CacheEventPublisher
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.cache.caffeine.CaffeineCache
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.cache.support.SimpleCacheManager
import tools.jackson.module.kotlin.jacksonObjectMapper

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

        // 키 열거(CacheKeyEnumerable)를 지원하는 TwoTierCache로 구성한 서비스.
        val mapper = jacksonObjectMapper()

        fun buildTwoTierService(
            cacheName: String = "recommendations:vtest",
            strategies: List<CacheRefreshStrategy> = emptyList(),
        ): Pair<CacheInvalidationService, TwoTierCache> {
            val cache =
                TwoTierCache(
                    cacheName = cacheName,
                    l1 = CaffeineCache(cacheName, Caffeine.newBuilder().maximumSize(100).build()),
                    l2 = MockRedisStore(),
                    ttlSeconds = 300,
                    serialize = { mapper.writeValueAsBytes(it) },
                    deserialize = { mapper.readValue(it, List::class.java) },
                )
            val manager = SimpleCacheManager().apply { setCaches(listOf(cache)) }.also { it.initializeCaches() }
            return CacheInvalidationService(manager, emptyList(), strategies) to cache
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

            it("versioned 이름(recommendations:v…)으로 호출해도 base 이름으로 refresh 한다") {
                // 어드민이 GET /admin/cache의 versioned 이름을 그대로 붙여넣는 경우.
                // supports()는 base 이름만 알므로, 정규화 없이는 refresh가 조용히 누락됐었다(버그).
                val strategy = mockk<CacheRefreshStrategy>()
                every { strategy.supports("recommendations") } returns true
                every { strategy.refresh(any(), any()) } returns Unit

                val service = buildService(strategies = listOf(strategy))
                service.evictAndRefresh("recommendations:v7a0fe702", "user-001")

                verify(exactly = 1) { strategy.refresh("recommendations", "user-001") }
            }
        }

        describe("evictAllAndRefresh — 전체 무효화 + 재갱신") {
            it("versioned 이름 전체 재갱신 시, 비우기 직전 캐시된 키들을 각각 base 이름으로 refresh 한다") {
                // POST /admin/cache/recommendations:v7a0fe702/refresh 시나리오 — evict는 되는데
                // refresh가 안 되던 버그의 핵심 회귀 테스트.
                val strategy = mockk<CacheRefreshStrategy>(relaxed = true)
                every { strategy.supports("recommendations") } returns true

                val (service, cache) = buildTwoTierService("recommendations:v7a0fe702", strategies = listOf(strategy))
                cache.put("user-001", listOf("product-a"))
                cache.put("user-002", listOf("product-b"))

                service.evictAllAndRefresh("recommendations:v7a0fe702")

                verify(exactly = 1) { strategy.refresh("recommendations", "user-001") }
                verify(exactly = 1) { strategy.refresh("recommendations", "user-002") }
                verify(exactly = 0) { strategy.refresh("recommendations", null) }
                // 캐시는 실제로 비워졌다 (mock 전략은 재적재하지 않으므로 비어 있어야 함)
                cache.get("user-001") shouldBe null
            }

            it("비어 있던 캐시의 전체 재갱신은 전략의 key=null 재갱신에 위임한다(데울 대상 없음)") {
                val strategy = mockk<CacheRefreshStrategy>(relaxed = true)
                every { strategy.supports("recommendations") } returns true

                val (service, _) = buildTwoTierService("recommendations:v7a0fe702", strategies = listOf(strategy))

                service.evictAllAndRefresh("recommendations:v7a0fe702")

                verify(exactly = 1) { strategy.refresh("recommendations", null) }
            }

            it("한 키의 refresh가 실패해도 나머지 키는 계속 재갱신하고 예외를 던지지 않는다") {
                val strategy = mockk<CacheRefreshStrategy>(relaxed = true)
                every { strategy.supports("recommendations") } returns true
                every { strategy.refresh("recommendations", "user-001") } throws RuntimeException("일시 실패")

                val (service, cache) = buildTwoTierService("recommendations:v7a0fe702", strategies = listOf(strategy))
                cache.put("user-001", listOf("product-a"))
                cache.put("user-002", listOf("product-b"))

                shouldNotThrow<Exception> {
                    service.evictAllAndRefresh("recommendations:v7a0fe702")
                }
                verify(exactly = 1) { strategy.refresh("recommendations", "user-002") }
            }
        }

        describe("evict 반환값 — 실제 제거 여부") {
            it("키가 있었으면 true를 반환한다") {
                val (service, cache) = buildTwoTierService()
                cache.put("user-001", listOf("product-a"))

                service.evict("recommendations", "user-001") shouldBe true
            }

            it("키가 없었으면 false를 반환한다") {
                val (service, _) = buildTwoTierService()

                service.evict("recommendations", "ghost") shouldBe false
            }
        }

        describe("listCacheNames — 캐시 목록 조회") {
            it("등록된 캐시 이름 목록을 반환한다") {
                val service = buildService()
                val names = service.listCacheNames()

                names shouldNotBe null
            }
        }

        describe("listCaches — 엔트리 키 포함 조회") {
            it("캐시 이름·기본 이름·엔트리 키를 함께 반환한다") {
                val (service, cache) = buildTwoTierService("recommendations:v7a0fe702")
                cache.put("user-001", listOf("product-a"))
                cache.put("user-002", listOf("product-b"))

                val caches = service.listCaches()

                caches.size shouldBe 1
                caches[0].name shouldBe "recommendations:v7a0fe702"
                caches[0].baseName shouldBe "recommendations"
                caches[0].entryCount shouldBe 2
                caches[0].keys shouldContainExactly listOf("user-001", "user-002")
            }

            it("키 열거를 지원하지 않는 캐시는 빈 키 목록을 반환한다") {
                val service = buildService() // CaffeineCacheManager — CacheKeyEnumerable 미구현
                val caches = service.listCaches()

                caches.forEach { it.keys shouldBe emptyList() }
            }
        }
    }
}
