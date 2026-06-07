package com.investhub.adapter.`in`.lifecycle

import com.investhub.application.service.WarmupService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component

/**
 * ApplicationReadyEvent 수신 시 웜업을 자동 실행하는 컴포넌트.
 *
 * 웜업 완료 후 [StartupReadyTracker.onWarmupCompleted]를 호출해
 * K8s Probe 도착까지의 gap 측정을 시작한다.
 *
 * K8s 배포 흐름:
 * 1. Pod 기동
 * 2. ApplicationReadyEvent → WarmupInvoker.onApplicationEvent() 실행
 * 3. 웜업 완료 → StartupReadyTracker.onWarmupCompleted()
 * 4. HealthController.startup() → 200 반환 (startup probe 통과)
 * 5. HealthController.ready() → 200 반환 (readiness probe 통과)
 * 6. K8s가 Service 엔드포인트 목록에 Pod 추가 (실제 트래픽 시작)
 */
@Component
class WarmupInvoker(
    private val warmupService: WarmupService,
    private val tracker: StartupReadyTracker,
) : ApplicationListener<ApplicationReadyEvent> {
    private val log = KotlinLogging.logger {}

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        runCatching {
            warmupService.warmUp()
        }.onFailure { ex ->
            log.error(ex) { "[WARMUP] 웜업 실패" }
        }
        tracker.onWarmupCompleted()
    }
}
