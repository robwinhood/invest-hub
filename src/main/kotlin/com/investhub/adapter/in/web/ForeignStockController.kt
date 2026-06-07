package com.investhub.adapter.`in`.web

import com.investhub.adapter.`in`.web.dto.ForeignStockSectionResponse
import com.investhub.application.port.input.GetForeignStockPortfolioUseCase
import com.investhub.domain.dashboard.ForeignStockPortfolioResult
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 해외 주식 잔고 **단독** 리소스 엔드포인트 (실시간 도메인).
 *
 * 왜 별도 엔드포인트인가 (ADR-008):
 * - 실시간성이 가장 높아 캐시하면 안 된다 → `no-store`. 잘못된 잔고 노출(금융 사고) 방지.
 * - 모바일 "당겨서 새로고침"으로 **이 섹션만** 자주 갱신하는 시나리오를 지원한다.
 *   (집계를 다시 부르면 자산·추천까지 불필요하게 재조회 → 자원 낭비)
 */
@RestController
@RequestMapping("/api/v1/investment")
class ForeignStockController(
    private val getForeignStockPortfolioUseCase: GetForeignStockPortfolioUseCase,
) {
    @GetMapping("/foreign-stocks")
    fun getForeignStocks(
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<ForeignStockSectionResponse> {
        val result = getForeignStockPortfolioUseCase.getForeignStockPortfolio(userId)
        val body = ForeignStockSectionResponse.from(result)
        return when (result) {
            is ForeignStockPortfolioResult.Success ->
                ResponseEntity
                    .ok()
                    .cacheControl(CacheControl.noStore())
                    .body(body)
            is ForeignStockPortfolioResult.Failure ->
                ResponseEntity
                    .status(result.reason.toHttpStatus())
                    .cacheControl(CacheControl.noStore())
                    .body(body)
        }
    }
}
