package com.investhub.application.port.input

import com.investhub.domain.dashboard.RecommendedProductsResult

/**
 * 추천 투자 상품 단독 조회 유스케이스 (저실시간 도메인).
 *
 * 캐시 가치가 높아(L1+L2, 5분 TTL) 단독 엔드포인트에 클라이언트 캐시(`Cache-Control`)를
 * 적용하기 적합하다. 이 입력 포트로 추천만 독립 조회·갱신한다.
 */
interface GetRecommendedProductsUseCase {
    fun getRecommendedProducts(userId: String): RecommendedProductsResult
}
