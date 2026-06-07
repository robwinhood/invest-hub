package com.investhub.config

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Virtual Thread 독립 Executor 구성 검증.
 *
 * 핵심 검증 내용:
 * 1. 각 executor의 스레드 이름이 역할별로 정확히 구분된다.
 * 2. 어댑터별로 독립 executor를 사용한다 (공유 executor 미사용).
 * 3. 한 executor가 블로킹되어도 다른 executor는 계속 동작한다 (장애 격리).
 * 4. 모든 스레드가 Virtual Thread임을 보장한다.
 */
class VirtualThreadIsolationTest : DescribeSpec() {
    private val meterRegistry = SimpleMeterRegistry()
    private val config = VirtualThreadConfig()

    private val accountExecutor = config.accountExecutor(meterRegistry)
    private val partnerExecutor = config.partnerExecutor(meterRegistry)
    private val recommendationExecutor = config.recommendationExecutor(meterRegistry)
    private val serviceExecutor = config.serviceExecutor(meterRegistry)

    init {
        describe("스레드 이름 — 역할별 식별 가능성") {
            it("account executor의 스레드 이름은 'account-vt-'로 시작한다") {
                val threadName = AtomicReference<String>()
                CompletableFuture
                    .runAsync({
                        threadName.set(Thread.currentThread().name)
                    }, accountExecutor)
                    .join()

                threadName.get() shouldStartWith "account-vt-"
            }

            it("partner executor의 스레드 이름은 'partner-vt-'로 시작한다") {
                val threadName = AtomicReference<String>()
                CompletableFuture
                    .runAsync({
                        threadName.set(Thread.currentThread().name)
                    }, partnerExecutor)
                    .join()

                threadName.get() shouldStartWith "partner-vt-"
            }

            it("recommendation executor의 스레드 이름은 'recommendation-vt-'로 시작한다") {
                val threadName = AtomicReference<String>()
                CompletableFuture
                    .runAsync({
                        threadName.set(Thread.currentThread().name)
                    }, recommendationExecutor)
                    .join()

                threadName.get() shouldStartWith "recommendation-vt-"
            }

            it("service executor의 스레드 이름은 'service-vt-'로 시작한다") {
                val threadName = AtomicReference<String>()
                CompletableFuture
                    .runAsync({
                        threadName.set(Thread.currentThread().name)
                    }, serviceExecutor)
                    .join()

                threadName.get() shouldStartWith "service-vt-"
            }

            it("네 executor는 모두 서로 다른 이름 prefix를 가진다") {
                val names =
                    listOf(
                        AtomicReference<String>(),
                        AtomicReference<String>(),
                        AtomicReference<String>(),
                        AtomicReference<String>(),
                    )
                val executors = listOf(accountExecutor, partnerExecutor, recommendationExecutor, serviceExecutor)

                executors
                    .zip(names)
                    .map { (exec, ref) ->
                        CompletableFuture.runAsync({ ref.set(Thread.currentThread().name) }, exec)
                    }.forEach { it.join() }

                val prefixes =
                    names.map { name ->
                        name.get().substringBeforeLast("-")
                    }
                prefixes.toSet().size shouldBe 4
            }
        }

        describe("Virtual Thread 보장") {
            it("account executor는 Virtual Thread를 생성한다") {
                val isVirtual = AtomicReference<Boolean>()
                CompletableFuture
                    .runAsync({
                        isVirtual.set(Thread.currentThread().isVirtual)
                    }, accountExecutor)
                    .join()

                isVirtual.get() shouldBe true
            }

            it("partner executor는 Virtual Thread를 생성한다") {
                val isVirtual = AtomicReference<Boolean>()
                CompletableFuture
                    .runAsync({
                        isVirtual.set(Thread.currentThread().isVirtual)
                    }, partnerExecutor)
                    .join()

                isVirtual.get() shouldBe true
            }

            it("recommendation executor는 Virtual Thread를 생성한다") {
                val isVirtual = AtomicReference<Boolean>()
                CompletableFuture
                    .runAsync({
                        isVirtual.set(Thread.currentThread().isVirtual)
                    }, recommendationExecutor)
                    .join()

                isVirtual.get() shouldBe true
            }

            it("service executor는 Virtual Thread를 생성한다") {
                val isVirtual = AtomicReference<Boolean>()
                CompletableFuture
                    .runAsync({
                        isVirtual.set(Thread.currentThread().isVirtual)
                    }, serviceExecutor)
                    .join()

                isVirtual.get() shouldBe true
            }
        }

        describe("장애 격리 — 한 executor 블로킹이 다른 executor에 영향을 주지 않는다") {
            it("partner executor 블로킹 중에도 account executor는 즉시 응답한다") {
                val partnerStarted = CountDownLatch(1)
                val accountCompleted = AtomicReference<Boolean>(false)

                // partner executor에서 장기 블로킹 작업 시작
                val partnerFuture =
                    CompletableFuture.runAsync({
                        partnerStarted.countDown()
                        Thread.sleep(3000) // 3초 블로킹
                    }, partnerExecutor)

                // partner가 시작될 때까지 대기
                partnerStarted.await(1, TimeUnit.SECONDS)

                // partner가 블로킹 중인 동안 account는 독립 executor에서 즉시 실행
                val start = System.currentTimeMillis()
                CompletableFuture
                    .runAsync({
                        Thread.sleep(50) // account 정상 지연
                        accountCompleted.set(true)
                    }, accountExecutor)
                    .get(500, TimeUnit.MILLISECONDS) // 500ms 내 완료 기대

                val elapsed = System.currentTimeMillis() - start

                accountCompleted.get() shouldBe true
                elapsed shouldBe elapsed.coerceAtMost(500) // partner 블로킹과 무관하게 빠름

                partnerFuture.cancel(true)
            }

            it("account executor와 partner executor는 서로 다른 스레드를 사용한다") {
                val accountThread = AtomicReference<Thread>()
                val partnerThread = AtomicReference<Thread>()

                val accountFuture =
                    CompletableFuture.runAsync({
                        accountThread.set(Thread.currentThread())
                        Thread.sleep(50)
                    }, accountExecutor)

                val partnerFuture =
                    CompletableFuture.runAsync({
                        partnerThread.set(Thread.currentThread())
                        Thread.sleep(50)
                    }, partnerExecutor)

                CompletableFuture.allOf(accountFuture, partnerFuture).join()

                accountThread.get() shouldNotBe null
                partnerThread.get() shouldNotBe null
                accountThread.get() shouldNotBe partnerThread.get()
                accountThread.get().name shouldContain "account-vt-"
                partnerThread.get().name shouldContain "partner-vt-"
            }
        }

        describe("병렬 실행 — 네 executor가 동시에 동작한다") {
            it("네 executor의 작업이 동시에 실행된다 (순차 합산보다 짧은 시간)") {
                val delay = 100L

                val start = System.currentTimeMillis()
                val futures =
                    listOf(
                        CompletableFuture.runAsync({ Thread.sleep(delay) }, accountExecutor),
                        CompletableFuture.runAsync({ Thread.sleep(delay) }, partnerExecutor),
                        CompletableFuture.runAsync({ Thread.sleep(delay) }, recommendationExecutor),
                        CompletableFuture.runAsync({ Thread.sleep(delay) }, serviceExecutor),
                    )
                CompletableFuture.allOf(*futures.toTypedArray()).join()
                val elapsed = System.currentTimeMillis() - start

                // 순차라면 400ms+, 병렬이면 ~100ms
                elapsed shouldBe elapsed.coerceAtMost(delay * 2 + 50)
            }
        }

        describe("Micrometer 메트릭 등록") {
            it("account executor 메트릭이 MeterRegistry에 등록된다") {
                CompletableFuture.runAsync({ /* no-op */ }, accountExecutor).join()

                val meters = meterRegistry.meters.map { it.id.name }
                meters.any { it.startsWith("executor") } shouldBe true
            }
        }
    }
}
