package com.investhub.adapter.out.cache

import com.investhub.application.port.output.CacheEventPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Mock Redis Pub/Sub 기반 캐시 무효화 이벤트 발행자.
 *
 * 빈 이름이 `redisCacheEventPublisher`이므로, 존재하는 순간
 * [NoOpCacheEventPublisher]의 `@ConditionalOnMissingBean(name=["redisCacheEventPublisher"])`가
 * 충족되어 NoOp이 자동 비활성화된다. (실 Redis 도입 시 이 클래스만 교체)
 *
 * 역할: 무효화가 일어난 키를 [MockRedisStore]의 채널에 발행한다.
 * 다른 Pod의 [L1EvictionSubscriber]가 이를 수신해 각자의 L1을 비운다.
 * (L2는 공유 저장소라 [TwoTierCache.evict]에서 이미 1회 제거되어 전 Pod에 반영된다.)
 */
@Component("redisCacheEventPublisher")
class MockRedisCacheEventPublisher(
    private val redis: MockRedisStore,
) : CacheEventPublisher {
    private val log = KotlinLogging.logger {}

    override fun publishEviction(
        cacheBaseName: String,
        key: String?,
    ) {
        val message = "$cacheBaseName|${key ?: WILDCARD}"
        redis.publish(EVICTION_CHANNEL, message)
        log.info { "[CACHE] 분산 무효화 발행(Mock Redis Pub/Sub) — channel=$EVICTION_CHANNEL, msg=$message" }
    }

    companion object {
        const val EVICTION_CHANNEL = "cache:eviction"
        const val WILDCARD = "*"
    }
}
