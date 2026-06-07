package com.investhub.adapter.`in`.web

import com.investhub.adapter.`in`.lifecycle.StartupReadyTracker
import com.investhub.application.service.WarmupService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * K8s 프로브 전용 헬스체크 컨트롤러.
 *
 * 관리 포트(8081)의 Spring Actuator와 별개로, API 포트(8080)에서 직접 Probe를 서비스한다.
 * K8s는 API 포트로 Probe를 호출하도록 설정해야 한다.
 *
 * 프로브 종류와 역할:
 *
 * [Startup Probe] GET /health/startup
 *   - 웜업이 완료됐는가? 완료 전 503 반환 → K8s가 재시도.
 *   - 웜업 완료 후 200 → K8s가 Liveness/Readiness Probe 시작.
 *   - 웜업 진행 중(isRunning)이면 즉시 503 반환 → probe timeout 방지.
 *
 * [Readiness Probe] GET /health/ready
 *   - 이 Pod가 트래픽을 받을 준비가 됐는가?
 *   - 웜업 미완료 시 503 → K8s Service 엔드포인트에서 제외.
 *   - [StartupReadyTracker.recordFirstProbe]를 통해 ready→첫 probe gap을 메트릭으로 기록.
 *
 * [Liveness Probe] GET /health/live
 *   - 이 Pod가 살아있는가? Spring Context가 동작하면 200.
 *   - 웜업 상태와 무관 — 웜업 실패해도 Pod를 재시작시키면 안 된다.
 */
@RestController
class HealthController(
    private val warmupService: WarmupService,
    private val tracker: StartupReadyTracker,
) {
    /**
     * Startup Probe 전용.
     *
     * 웜업 완료 여부를 확인한다.
     * - 이미 완료: 200 즉시 반환
     * - 웜업 진행 중: 503 즉시 반환 (블로킹하지 않음 — K8s probe timeout 방지)
     * - 아직 시작 안 됨(로컬 환경): 동기 실행 후 200 반환
     */
    @GetMapping("/health/startup")
    fun startup(): ResponseEntity<String> {
        if (warmupService.isCompleted) return ResponseEntity.ok("OK")
        if (warmupService.isRunning) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("warming up")
        warmupService.warmUp()
        return ResponseEntity.ok("OK")
    }

    /**
     * Readiness Probe 전용.
     *
     * 웜업이 완료된 경우에만 200을 반환한다.
     * 미완료 시 503 → K8s가 이 Pod를 Service 엔드포인트에서 제외.
     * 최초 호출 시 [StartupReadyTracker]에 probe 도착 시각을 기록해 gap 메트릭을 생성한다.
     */
    @GetMapping("/health/ready")
    fun ready(): ResponseEntity<String> {
        if (!warmupService.isCompleted) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("not ready")
        }
        tracker.recordFirstProbe()
        return ResponseEntity.ok("OK")
    }

    /**
     * Liveness Probe 전용.
     *
     * Spring Context가 살아있으면 항상 200을 반환한다.
     * 웜업 실패·진행 여부와 무관 — 실패해도 Pod를 재시작시키면 안 된다.
     */
    @GetMapping("/health/live")
    fun live(): ResponseEntity<String> = ResponseEntity.ok("OK")
}
