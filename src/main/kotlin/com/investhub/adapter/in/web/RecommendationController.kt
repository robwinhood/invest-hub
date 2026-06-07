package com.investhub.adapter.`in`.web

import com.investhub.adapter.`in`.web.dto.RecommendationSectionResponse
import com.investhub.application.port.input.GetRecommendedProductsUseCase
import com.investhub.domain.dashboard.RecommendedProductsResult
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * 추천 투자 상품 **단독** 리소스 엔드포인트 (저실시간 도메인).
 *
 * 왜 별도 엔드포인트인가 (ADR-008):
 * - 실시간성이 낮아 캐시 가치가 가장 크다 → 서버 측 L1+L2 캐시(5분)에 더해,
 *   클라이언트 캐시 `private, max-age=300`으로 재진입 시 재조회를 줄인다(HTTP TTL을 L2 TTL과 정렬).
 * - 사용자 맞춤(per-user) 데이터라 `private`(공유 캐시 금지). 교차 사용자 offload는 서버 L2가 담당.
 * - 추천 엔진 장애가 자산·주식 조회에 영향을 주지 않도록 독립 노출.
 */
@RestController
@RequestMapping("/api/v1/investment")
class RecommendationController(
    private val getRecommendedProductsUseCase: GetRecommendedProductsUseCase,
) {
    @GetMapping("/recommendations")
    fun getRecommendations(
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<RecommendationSectionResponse> {
        val result = getRecommendedProductsUseCase.getRecommendedProducts(userId)
        val body = RecommendationSectionResponse.from(result)
        return when (result) {
            is RecommendedProductsResult.Success ->
                ResponseEntity
                    .ok()
                    .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                    .body(body)
            is RecommendedProductsResult.Failure ->
                ResponseEntity
                    .status(result.reason.toHttpStatus())
                    .cacheControl(CacheControl.noStore())
                    .body(body)
        }
    }
}
