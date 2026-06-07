package com.investhub.adapter.`in`.web

import com.investhub.adapter.`in`.web.dto.InvestmentDashboardResponse
import com.investhub.application.port.input.GetInvestmentDashboardUseCase
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 투자 대시보드 **집계** 엔드포인트 — 첫 화면 1회 렌더용 (BFF).
 *
 * 세 도메인을 가상 스레드에서 병렬 조합해 단일 응답으로 제공한다(모바일 왕복 최소화).
 * 도메인별 독립 갱신·캐싱은 단독 리소스 엔드포인트(`/assets`·`/foreign-stocks`·`/recommendations`)가 담당한다. (ADR-008)
 *
 * 집계 응답은 실시간(주식)을 포함하므로 **`no-store`** — HTTP 캐시 대상이 아니다.
 * 캐시 가능한 데이터는 단독 엔드포인트에서 속성별 `Cache-Control`로 노출된다.
 */
@RestController
@RequestMapping("/api/v1/investment")
class InvestmentDashboardController(
    private val getDashboardUseCase: GetInvestmentDashboardUseCase,
) {
    @GetMapping("/dashboard")
    fun getDashboard(
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<InvestmentDashboardResponse> {
        val dashboard = getDashboardUseCase.getDashboard(userId)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(InvestmentDashboardResponse.from(dashboard))
    }
}
