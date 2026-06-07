package com.investhub.adapter.out.cache

import com.github.benmanes.caffeine.cache.Caffeine
import com.investhub.domain.product.InvestmentProduct
import com.investhub.domain.product.ProductType
import com.investhub.domain.product.RiskLevel
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.cache.caffeine.CaffeineCache
import tools.jackson.core.type.TypeReference
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal

/**
 * L1(Caffeine) + L2(Mock Redis) 2계층 캐시 동작 검증.
 *
 * 핵심 시나리오:
 * - put은 L1·L2 양쪽에 저장한다.
 * - L1 미스라도 L2에서 복원(직렬화 라운드트립)하고 L1으로 승격한다.
 * - evict/clear는 L1·L2를 **모두** 비운다.
 * - 분산 무효화 발행자/구독자가 Pub/Sub으로 동작한다.
 */
class TwoTierCacheTest :
    DescribeSpec({
        val mapper = jacksonObjectMapper()
        val listType = object : TypeReference<List<InvestmentProduct>>() {}

        fun sampleProducts() =
            listOf(
                InvestmentProduct(
                    productId = "PROD-001",
                    productType = ProductType.ETF,
                    name = "미국 S&P500 ETF",
                    description = "분산 투자 대표 ETF",
                    expectedReturnRate = BigDecimal("8.50"),
                    riskLevel = RiskLevel.MEDIUM,
                    minimumAmount = BigDecimal("10000"),
                ),
                InvestmentProduct(
                    productId = "PROD-002",
                    productType = ProductType.BOND,
                    name = "국고채 10년",
                    description = "안전 자산",
                    expectedReturnRate = null,
                    riskLevel = RiskLevel.VERY_LOW,
                    minimumAmount = BigDecimal("1000000"),
                ),
            )

        fun build(): Pair<TwoTierCache, MockRedisStore> {
            val l2 = MockRedisStore()
            val l1 = CaffeineCache("recommendations:vtest", Caffeine.newBuilder().maximumSize(100).build())
            val cache =
                TwoTierCache(
                    cacheName = "recommendations:vtest",
                    l1 = l1,
                    l2 = l2,
                    ttlSeconds = 300,
                    serialize = { mapper.writeValueAsBytes(it) },
                    deserialize = { mapper.readValue(it, listType) },
                )
            return cache to l2
        }

        describe("TwoTierCache — 2계층 저장/조회") {
            it("put은 L1과 L2 양쪽에 저장한다") {
                val (cache, l2) = build()

                cache.put("user-001", sampleProducts())

                l2.size() shouldBe 1
                cache.get("user-001").shouldNotBeNull()
            }

            it("L1 미스 시 L2에서 복원하며 값이 원본과 동일하다 (직렬화 라운드트립)") {
                val (cache, l2) = build()
                val original = sampleProducts()
                cache.put("user-001", original)

                cache.evictLocalOnly("user-001") // L1만 비움 → L2엔 남아있음
                val restored = cache.get("user-001")?.get()

                restored shouldBe original
                l2.size() shouldBe 1 // L2는 그대로
            }
        }

        describe("TwoTierCache — 무효화는 L1·L2 모두 비운다") {
            it("evict는 L1과 L2를 모두 제거한다") {
                val (cache, l2) = build()
                cache.put("user-001", sampleProducts())

                cache.evict("user-001")

                l2.size() shouldBe 0
                cache.get("user-001").shouldBeNull()
            }

            it("clear는 L1과 L2를 모두 제거한다") {
                val (cache, l2) = build()
                cache.put("user-A", sampleProducts())
                cache.put("user-B", sampleProducts())

                cache.clear()

                l2.size() shouldBe 0
                cache.get("user-A").shouldBeNull()
                cache.get("user-B").shouldBeNull()
            }
        }

        describe("TwoTierCache — 키 열거 / 정확한 evict") {
            it("keys는 L1·L2 합집합을 원래 키로 환원해 반환한다") {
                val (cache, _) = build()
                cache.put("user-001", sampleProducts())
                cache.put("user-002", sampleProducts())

                cache.keys() shouldBe setOf("user-001", "user-002")
            }

            it("L1이 비어 L2에만 있어도 keys에 포함된다 (prefix 환원)") {
                val (cache, _) = build()
                cache.put("user-001", sampleProducts())
                cache.evictLocalOnly("user-001") // L1만 비움

                cache.keys() shouldBe setOf("user-001")
            }

            it("evictIfPresent는 키가 있으면 true, 없으면 false를 반환한다") {
                val (cache, _) = build()
                cache.put("user-001", sampleProducts())

                cache.evictIfPresent("user-001") shouldBe true
                cache.evictIfPresent("user-001") shouldBe false // 이미 제거됨
                cache.evictIfPresent("never-existed") shouldBe false
            }
        }

        describe("MockRedisStore — Mock Redis 동작") {
            it("evictIfPresent는 키 존재 여부를 정확히 반환한다") {
                val redis = MockRedisStore()
                redis.put("k", byteArrayOf(1), 300)

                redis.evictIfPresent("k") shouldBe true
                redis.evictIfPresent("k") shouldBe false
            }

            it("keysByPrefix는 prefix로 시작하는 만료되지 않은 키만 반환한다") {
                val redis = MockRedisStore()
                redis.put("recommendations::user-A", byteArrayOf(1), 300)
                redis.put("recommendations::user-B", byteArrayOf(2), 300)
                redis.put("other::user-C", byteArrayOf(3), 300)

                redis.keysByPrefix("recommendations::") shouldBe
                    setOf("recommendations::user-A", "recommendations::user-B")
            }

            it("evictByPrefix는 prefix로 시작하는 키만 제거한다") {
                val redis = MockRedisStore()
                redis.put("recommendations::user-A", byteArrayOf(1), 300)
                redis.put("recommendations::user-B", byteArrayOf(2), 300)
                redis.put("other::user-C", byteArrayOf(3), 300)

                redis.evictByPrefix("recommendations::")

                redis.size() shouldBe 1
                redis.get("other::user-C").shouldNotBeNull()
            }

            it("TTL이 만료되면 null을 반환한다") {
                val redis = MockRedisStore()
                redis.put("k", byteArrayOf(1), 0) // 즉시 만료
                Thread.sleep(10)

                redis.get("k").shouldBeNull()
            }

            it("publish 시 subscribe한 리스너가 호출된다") {
                val redis = MockRedisStore()
                val received = mutableListOf<String>()
                redis.subscribe("ch", { received.add(it) })

                redis.publish("ch", "hello")

                received shouldBe listOf("hello")
            }
        }

        describe("분산 무효화 — Pub/Sub 발행 형식") {
            it("MockRedisCacheEventPublisher는 'base|key' 형식으로 발행한다") {
                val redis = MockRedisStore()
                val publisher = MockRedisCacheEventPublisher(redis)
                val received = mutableListOf<String>()
                redis.subscribe(MockRedisCacheEventPublisher.EVICTION_CHANNEL, { received.add(it) })

                publisher.publishEviction("recommendations", "user-001")
                publisher.publishEviction("recommendations", null) // 전체 무효화

                received shouldBe listOf("recommendations|user-001", "recommendations|*")
            }
        }
    })
