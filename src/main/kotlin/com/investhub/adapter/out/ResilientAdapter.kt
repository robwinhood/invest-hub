package com.investhub.adapter.out

import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/**
 * CB → Bulkhead → TimeLimiter 데코레이션 패턴을 템플릿화한 기반 어댑터.
 *
 * 세 데이터 소스 어댑터가 동일한 내결함성 구조를 중복 작성하지 않도록 한다.
 * 새 어댑터 추가 시 portName 선언과 call() 구현만으로 동일한 보호가 자동 적용된다.
 *
 * 데코레이션 순서 근거:
 *   CB가 OPEN이면 Bulkhead·TimeLimiter 자원을 전혀 소모하지 않는다.
 *   Bulkhead가 꽉 차면 TimeLimiter Future를 생성하지 않는다.
 */
abstract class ResilientAdapter<T>(
    circuitBreakerRegistry: CircuitBreakerRegistry,
    bulkheadRegistry: BulkheadRegistry,
    timeLimiterRegistry: TimeLimiterRegistry,
    private val executor: ExecutorService,
) {
    protected abstract val portName: String

    private val circuitBreaker: CircuitBreaker by lazy {
        circuitBreakerRegistry.circuitBreaker(portName)
    }
    private val bulkhead: Bulkhead by lazy {
        bulkheadRegistry.bulkhead(portName)
    }
    private val timeLimiter: TimeLimiter by lazy {
        timeLimiterRegistry.timeLimiter(portName)
    }

    protected fun execute(call: () -> T): T =
        circuitBreaker.executeCallable {
            bulkhead.executeCallable {
                timeLimiter.executeFutureSupplier {
                    CompletableFuture.supplyAsync(call, executor)
                }
            }
        }
}
