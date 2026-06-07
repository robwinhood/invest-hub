package com.investhub.adapter.out

import com.github.benmanes.caffeine.cache.Caffeine
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.cache.caffeine.CaffeineCache
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * CachingResilientAdapter의 캐시 히트/미스 동작을 검증한다.
 *
 * 이 어댑터를 상속하면 캐시가 구조적으로 보장된다.
 * 이 테스트는 그 보장이 실제로 동작함을 증명한다.
 */
class CachingResilientAdapterTest : DescribeSpec() {
    private val cbRegistry = CircuitBreakerRegistry.ofDefaults()
    private val bhRegistry = BulkheadRegistry.ofDefaults()
    private val tlRegistry = TimeLimiterRegistry.ofDefaults()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    // 테스트마다 새 캐시와 카운터를 사용해 상태 오염 방지
    private fun buildAdapter(): Pair<TestCachingAdapter, AtomicInteger> {
        val callCount = AtomicInteger(0)
        val cache =
            CaffeineCache(
                "test-recommendations-${System.nanoTime()}",
                Caffeine.newBuilder().maximumSize(100).build(),
            )
        val adapter =
            TestCachingAdapter(
                cbRegistry,
                bhRegistry,
                tlRegistry,
                executor,
                cache,
                callCount,
            )
        return adapter to callCount
    }

    /**
     * 테스트 전용 어댑터 — callUncached 호출 횟수를 추적한다.
     */
    private inner class TestCachingAdapter(
        cbRegistry: CircuitBreakerRegistry,
        bhRegistry: BulkheadRegistry,
        tlRegistry: TimeLimiterRegistry,
        executor: java.util.concurrent.ExecutorService,
        cache: org.springframework.cache.Cache,
        private val callCount: AtomicInteger,
    ) : CachingResilientAdapter<String>(cbRegistry, bhRegistry, tlRegistry, executor, cache) {
        override val portName = "test"

        override fun callUncached(userId: String): String {
            callCount.incrementAndGet()
            return "data-for-$userId"
        }

        fun fetch(userId: String): String = executeWithCache(userId)
    }

    init {
        describe("CachingResilientAdapter — 캐시 동작") {

            it("첫 번째 호출은 외부 시스템을 실제로 호출한다") {
                val (adapter, count) = buildAdapter()

                adapter.fetch("user-001")

                count.get() shouldBe 1
            }

            it("동일 userId의 두 번째 호출은 캐시에서 응답하고 외부 호출을 생략한다") {
                val (adapter, count) = buildAdapter()

                adapter.fetch("user-001") // 캐시 미스 → 외부 호출
                adapter.fetch("user-001") // 캐시 히트 → 외부 호출 없음

                count.get() shouldBe 1
            }

            it("서로 다른 userId는 각각 독립적으로 캐시된다") {
                val (adapter, count) = buildAdapter()

                adapter.fetch("user-A") // 캐시 미스
                adapter.fetch("user-B") // 캐시 미스 (다른 키)
                adapter.fetch("user-A") // 캐시 히트
                adapter.fetch("user-B") // 캐시 히트

                count.get() shouldBe 2
            }

            it("캐시된 값은 첫 번째 호출 결과와 동일하다") {
                val (adapter, _) = buildAdapter()

                val first = adapter.fetch("user-001")
                val second = adapter.fetch("user-001")

                first shouldBe second
                first shouldBe "data-for-user-001"
            }
        }
    }
}
