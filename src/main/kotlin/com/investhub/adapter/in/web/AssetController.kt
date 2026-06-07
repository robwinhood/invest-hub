package com.investhub.adapter.`in`.web

import com.investhub.adapter.`in`.web.dto.AssetSectionResponse
import com.investhub.application.port.input.GetAssetSummaryUseCase
import com.investhub.domain.dashboard.AssetSummaryResult
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * 내 계좌/자산 **단독** 리소스 엔드포인트.
 *
 * 왜 집계(`/dashboard`)와 별도로 두나 (ADR-008):
 * - 자산은 갱신 빈도가 낮고 값이 안정적 → 짧은 클라이언트 캐시로 재조회를 줄일 수 있다.
 * - 사용자별 데이터이므로 `private`(공유 캐시 금지) + 30초 `max-age`.
 * - 집계에 묶이지 않아, 주식/추천 장애와 무관하게 독립 조회된다(서비스 독립성).
 */
@RestController
@RequestMapping("/api/v1/investment")
class AssetController(
    private val getAssetSummaryUseCase: GetAssetSummaryUseCase,
) {
    @GetMapping("/assets")
    fun getAssets(
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<AssetSectionResponse> {
        val result = getAssetSummaryUseCase.getAssetSummary(userId)
        val body = AssetSectionResponse.from(result)
        return when (result) {
            is AssetSummaryResult.Success ->
                ResponseEntity
                    .ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePrivate())
                    .body(body)
            is AssetSummaryResult.Failure ->
                ResponseEntity
                    .status(result.reason.toHttpStatus())
                    .cacheControl(CacheControl.noStore())
                    .body(body)
        }
    }
}
