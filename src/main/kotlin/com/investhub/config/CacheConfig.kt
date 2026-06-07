package com.investhub.config

import com.github.benmanes.caffeine.cache.Caffeine
import com.investhub.domain.product.InvestmentProduct
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cache.Cache
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCache
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

@Configuration
@EnableCaching
class CacheConfig {
    private val log = KotlinLogging.logger {}

    /**
     * 추천 상품 캐시 (TTL 5분).
     *
     * 캐시 이름에 [CacheKeyVersionGenerator]로 자동 생성된 버전 해시를 포함한다.
     * [InvestmentProduct] 클래스의 필드 구조가 변경되면 해시가 자동으로 바뀌어
     * Redis 전환 시 구 포맷 캐시 데이터와의 충돌이 원천 차단된다.
     * 개발자가 버전을 수동으로 올릴 필요가 없다.
     *
     * 현재 (Caffeine): 앱 재시작 시 자동 소멸 → 버전 충돌 없음.
     * 향후 (Redis): 동일 메커니즘으로 키 충돌 자동 방지.
     */
    @Bean("recommendationsCache")
    fun recommendationsCache(): Cache {
        val cacheName = CacheKeyVersionGenerator.versionedName("recommendations", InvestmentProduct::class)
        log.info { "추천 상품 캐시 이름: $cacheName (InvestmentProduct 구조 기반 자동 버전)" }
        return CaffeineCache(
            cacheName,
            Caffeine
                .newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(500)
                .build(),
        )
    }
}
