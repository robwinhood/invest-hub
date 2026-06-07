package com.investhub.config

import com.github.benmanes.caffeine.cache.Caffeine
import com.investhub.adapter.out.cache.TwoTierCache
import com.investhub.application.port.output.DistributedCacheStore
import com.investhub.domain.product.InvestmentProduct
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cache.Cache
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCache
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

/**
 * 추천 상품 캐시 = L1(로컬 Caffeine) + L2(분산 Mock Redis) 2계층 구성.
 *
 * - **L1 (Caffeine)**: Pod-로컬, 빠름, 5분 TTL. 대부분의 요청을 여기서 처리.
 * - **L2 (Mock Redis)**: 전 Pod 공유, 직렬화 저장, 5분 TTL. L1 미스 시 원본 호출 없이 흡수.
 *   → 신규 Pod·L1 만료 시에도 원격(추천 엔진) 호출을 줄여 자원 효율을 높인다.
 *
 * 캐시 이름에 [CacheKeyVersionGenerator]의 클래스 구조 해시를 포함한다.
 * **L2는 직렬화 바이트를 저장**하므로, [InvestmentProduct] 필드가 변경되면 해시가 바뀌어
 * 새 키를 사용 → 구 포맷과의 역직렬화 충돌이 원천 차단된다. (이중 캐시에서 비로소 실효를 갖는 장치)
 *
 * 무효화는 L1·L2를 모두 비우고(`TwoTierCache.evict`), 분산 전파는
 * `MockRedisCacheEventPublisher` → `L1EvictionSubscriber` 경로로 타 Pod의 L1까지 비운다.
 */
@Configuration
@EnableCaching
class CacheConfig {
    private val log = KotlinLogging.logger {}

    @Bean("recommendationsCache")
    fun recommendationsCache(
        distributedCacheStore: DistributedCacheStore,
        objectMapper: ObjectMapper,
    ): Cache {
        val cacheName = CacheKeyVersionGenerator.versionedName("recommendations", InvestmentProduct::class)
        log.info { "추천 상품 캐시(L1+L2) 이름: $cacheName (InvestmentProduct 구조 기반 자동 버전)" }

        val l1 =
            CaffeineCache(
                cacheName,
                Caffeine
                    .newBuilder()
                    .expireAfterWrite(TTL_MINUTES, TimeUnit.MINUTES)
                    .maximumSize(500)
                    .build(),
            )

        // L2 직렬화 타입: List<InvestmentProduct>. 값 타입을 아는 이 설정에서만 (de)serialize를 주입한다.
        val listType = object : TypeReference<List<InvestmentProduct>>() {}

        return TwoTierCache(
            cacheName = cacheName,
            l1 = l1,
            l2 = distributedCacheStore,
            ttlSeconds = TTL_MINUTES * 60,
            serialize = { value -> objectMapper.writeValueAsBytes(value) },
            deserialize = { bytes -> objectMapper.readValue(bytes, listType) },
        )
    }

    companion object {
        const val TTL_MINUTES = 5L
    }
}
