package com.investhub.adapter.out.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Component

/**
 * 분산 무효화 이벤트 구독자.
 *
 * [MockRedisCacheEventPublisher]가 발행한 무효화 이벤트를 수신해
 * 이 Pod의 L1(로컬 Caffeine)을 비운다 — 즉 "다른 Pod의 L1 무효화" 역할을 모사한다.
 *
 * 실 환경에서는 각 Pod의 이 구독자가 Redis Pub/Sub 메시지를 받아 자신의 L1을 비운다.
 * 단일 프로세스 Mock 환경에서는 발행한 Pod 자신이 구독자이기도 하므로,
 * L1-only evict는 [TwoTierCache.evict]가 이미 수행한 것과 멱등적으로 겹친다(무해).
 *
 * L2는 공유 저장소라 발행 측에서 1회 제거로 전 Pod에 반영되므로 여기선 **L1만** 비운다.
 */
@Component
class L1EvictionSubscriber(
    private val redis: MockRedisStore,
    private val cacheManager: CacheManager,
) {
    private val log = KotlinLogging.logger {}

    @PostConstruct
    fun subscribe() {
        redis.subscribe(MockRedisCacheEventPublisher.EVICTION_CHANNEL) { message ->
            handle(message)
        }
    }

    private fun handle(message: String) {
        val parts = message.split("|", limit = 2)
        if (parts.size != 2) {
            log.warn { "[CACHE] 무효화 메시지 형식 오류 — msg=$message" }
            return
        }
        val (baseName, key) = parts
        val targets =
            cacheManager.cacheNames
                .filter { it == baseName || it.startsWith("$baseName:") }
                .mapNotNull { cacheManager.getCache(it) }
                .filterIsInstance<TwoTierCache>()

        targets.forEach { cache ->
            if (key == MockRedisCacheEventPublisher.WILDCARD) {
                cache.clearLocalOnly()
            } else {
                cache.evictLocalOnly(key)
            }
        }
        log.debug { "[CACHE] 분산 무효화 수신 처리 — base=$baseName, key=$key, targets=${targets.size}" }
    }
}
