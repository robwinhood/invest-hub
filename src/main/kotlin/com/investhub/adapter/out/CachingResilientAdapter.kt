package com.investhub.adapter.out

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.springframework.cache.Cache
import java.util.concurrent.ExecutorService

/**
 * 캐시가 구조적으로 보장되는 어댑터 기반 클래스.
 *
 * 실시간성이 낮은 외부 호출(추천 엔진 등)은 이 클래스를 상속한다.
 * 상속하는 순간 캐시가 자동으로 적용되므로, 개발자가 캐시 추가를 누락할 수 없다.
 *
 * 실시간 데이터(해외 주식 등)는 [ResilientAdapter]를 직접 상속해 캐시를 배제한다.
 * 이 구분 자체가 데이터 속성에 대한 명시적 설계 결정이다.
 */
abstract class CachingResilientAdapter<T>(
    circuitBreakerRegistry: CircuitBreakerRegistry,
    bulkheadRegistry: BulkheadRegistry,
    timeLimiterRegistry: TimeLimiterRegistry,
    executor: ExecutorService,
    private val cache: Cache,
) : ResilientAdapter<T>(circuitBreakerRegistry, bulkheadRegistry, timeLimiterRegistry, executor) {
    private val log = KotlinLogging.logger {}

    @Suppress("UNCHECKED_CAST")
    protected fun executeWithCache(userId: String): T {
        val cached = cache.get(userId)
        if (cached != null) {
            log.debug { "캐시 히트: portName=$portName" }
            return cached.get() as T
        }
        log.debug { "캐시 미스: portName=$portName — 외부 호출 시작" }
        return execute { callUncached(userId) }.also { result ->
            cache.put(userId, result)
        }
    }

    protected abstract fun callUncached(userId: String): T
}
