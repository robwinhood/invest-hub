package com.investhub.adapter.out.cache

import com.investhub.application.port.output.RecommendationPort
import com.investhub.application.service.CacheRefreshStrategy
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * 추천 상품 캐시 재갱신 전략.
 *
 * 무효화 직후 [RecommendationPort]를 호출해 캐시를 사전에 채운다.
 * [CachingResilientAdapter]가 응답을 캐시에 자동 저장하므로
 * 별도 캐시 저장 로직이 필요 없다.
 *
 * 전체 재갱신(key=null): 이 전략 단독으로는 미지원(추천은 userId 단위라 키 없이 전체를
 * 불러올 수 없다). 대신 [com.investhub.application.service.CacheInvalidationService.evictAllAndRefresh]가
 * **비우기 직전 캐시에 있던 userId들을 수집해** 키별로 이 전략의 [refresh]를 호출한다.
 * 따라서 `POST /admin/cache/recommendations/refresh`(키 없는 전체 재갱신)도 현재 캐시돼 있던
 * 사용자 전체를 실제로 다시 데운다. key=null 분기는 그 외(빈 캐시 등) 경우의 안전한 no-op이다.
 */
@Component
class RecommendationCacheRefreshStrategy(
    private val recommendationPort: RecommendationPort,
) : CacheRefreshStrategy {
    private val log = KotlinLogging.logger {}

    override fun supports(cacheBaseName: String): Boolean = cacheBaseName == "recommendations"

    override fun refresh(
        cacheBaseName: String,
        key: String?,
    ) {
        if (key == null) {
            log.warn { "[CACHE] 추천 캐시 전체 재갱신 미지원 — 특정 userId를 지정하세요" }
            return
        }
        log.debug { "[CACHE] 추천 캐시 재갱신 시작: userId=$key" }
        recommendationPort.getRecommendedProducts(userId = key)
    }
}
