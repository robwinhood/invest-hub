package com.investhub.application.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis

/**
 * 애플리케이션 시작 시 웜업을 조율하는 서비스.
 *
 * 역할:
 * - 등록된 모든 [Warmer] 구현체를 순서대로 실행한다.
 * - 완료 후 [isCompleted]를 true로 설정해 startup probe가 200을 반환하도록 한다.
 * - [HealthController]의 startup probe가 이 상태를 폴링한다.
 *
 * 웜업이 필요한 이유 (100K TPS 환경):
 * - JVM JIT: 초기 요청은 인터프리터 모드로 처리 → 웜업 없으면 첫 트래픽에 레이턴시 급등
 * - 추천 캐시: 첫 요청마다 추천 엔진 원격 호출 → 5분 캐시 사전 적재로 응답 시간 안정화
 * - CB 메트릭 초기화: Resilience4j 슬라이딩 윈도우가 비어있으면 첫 실패에 과민 반응
 */
@Service
class WarmupService(
    private val warmers: List<Warmer>,
) {
    private val log = KotlinLogging.logger {}
    private val _completed = AtomicBoolean(false)
    private val _running = AtomicBoolean(false)

    val isCompleted: Boolean get() = _completed.get()
    val isRunning: Boolean get() = _running.get()

    fun warmUp() {
        if (_completed.get()) return
        if (!_running.compareAndSet(false, true)) return

        try {
            log.info { "[WARMUP] 시작 — 총 ${warmers.size}개 Warmer" }
            val totalMs =
                measureTimeMillis {
                    warmers.forEach { warmer ->
                        val ms =
                            measureTimeMillis {
                                runCatching { warmer.warm() }
                                    .onFailure { ex ->
                                        log.warn(ex) { "[WARMUP] ${warmer.name()} 실패 — 계속 진행" }
                                    }
                            }
                        log.info { "[WARMUP] ${warmer.name()} 완료 (${ms}ms)" }
                    }
                }
            _completed.set(true)
            log.info { "[WARMUP] 전체 완료 (${totalMs}ms)" }
        } finally {
            _running.set(false)
        }
    }
}
