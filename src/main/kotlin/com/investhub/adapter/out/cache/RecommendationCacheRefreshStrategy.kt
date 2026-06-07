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
 * 전체 재갱신(key=null): 현재는 지원하지 않는다.
 * 추천 상품은 userId 단위 캐시이므로 특정 userId 없이 전체를 미리 불러오기 어렵다.
 * 필요하다면 활성 사용자 목록을 별도로 관리해 일괄 재갱신하는 방식으로 확장할 수 있다.
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
