package com.investhub.adapter.`in`.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 웜업 완료 후 K8s가 실제 트래픽을 보내기까지의 지연을 추적한다.
 *
 * 배경:
 * 신규 Pod가 Readiness Probe를 통과해도 K8s Service의 엔드포인트 목록에 추가되기까지
 * 수십ms~수초의 지연이 발생한다. 이 시간 동안 Pod는 트래픽을 받지 못한 채 준비 상태다.
 * 이 gap을 측정해 배포 전략(rollout 속도 등) 결정에 활용한다.
 *
 * 메트릭: [METRIC_NAME]
 * - probe 기록 전: NaN → Datadog/Prometheus에 전송되지 않음 (0으로 오염 방지)
 * - probe 기록 후: 실제 gap(ms) → Datadog에 정확히 반영
 */
@Component
class StartupReadyTracker(
    meterRegistry: MeterRegistry,
) {
    private val log = KotlinLogging.logger {}

    private val warmupCompletedAt = AtomicLong(0L)
    private val firstProbeRecorded = AtomicBoolean(false)
    private val recordedGapMs = AtomicLong(-1L)

    init {
        // recordedGapMs가 -1(sentinel)이면 NaN → Datadog 전송 안 됨
        Gauge
            .builder(METRIC_NAME, recordedGapMs) { if (it.get() < 0L) Double.NaN else it.get().toDouble() }
            .description("Gap between warmup complete and first K8s readiness probe (ms)")
            .register(meterRegistry)
    }

    /** WarmupService 완료 시점에 호출 */
    fun onWarmupCompleted() {
        warmupCompletedAt.set(System.currentTimeMillis())
        log.info { "[STARTUP] 웜업 완료 → K8s Readiness Probe 대기 시작" }
    }

    /** HealthController readiness probe 호출 시 최초 1회 기록 */
    fun recordFirstProbe() {
        if (firstProbeRecorded.get()) return
        val ready = warmupCompletedAt.get()
        if (ready == 0L) return
        if (!firstProbeRecorded.compareAndSet(false, true)) return

        runCatching {
            val gapMs = System.currentTimeMillis() - ready
            recordedGapMs.set(gapMs)
            log.info { "[STARTUP] 첫 번째 K8s Probe 수신 — ready→probe gap: ${gapMs}ms" }
        }.onFailure { ex ->
            log.warn(ex) { "[STARTUP] gap 기록 실패" }
        }
    }

    fun isFirstProbeRecorded(): Boolean = firstProbeRecorded.get()

    companion object {
        const val METRIC_NAME = "startup.ready_probe_gap_ms"
    }
}
