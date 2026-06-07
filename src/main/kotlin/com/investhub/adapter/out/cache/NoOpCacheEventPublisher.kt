package com.investhub.adapter.out.cache

import com.investhub.application.port.output.CacheEventPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component

/**
 * Redis 미연동 환경의 기본 캐시 이벤트 발행 구현체.
 *
 * 현재 동작: 로컬 캐시 무효화 후 로그만 출력한다.
 * 각 Pod가 독립 Caffeine 캐시를 가지므로 다른 Pod에는 전파되지 않는다.
 *
 * Redis 연동 시:
 *   1. `RedisCacheEventPublisher`(@Component)를 구현하면 이 빈은 자동 비활성화된다.
 *   2. Redis Pub/Sub으로 이벤트를 발행하면 모든 Pod의 로컬 캐시가 동기화된다.
 *
 * ```yaml
 * # Redis 연동 예시 (향후)
 * spring.data.redis:
 *   host: redis-host
 *   port: 6379
 * ```
 */
@Component
@ConditionalOnMissingBean(name = ["redisCacheEventPublisher"])
class NoOpCacheEventPublisher : CacheEventPublisher {
    private val log = KotlinLogging.logger {}

    override fun publishEviction(
        cacheBaseName: String,
        key: String?,
    ) {
        if (key == null) {
            log.info {
                "[CACHE] 전체 무효화 — cache=$cacheBaseName " +
                    "(Redis Pub/Sub 미설정: 현재 Pod만 적용. " +
                    "다른 Pod는 TTL 만료까지 stale 데이터 사용)"
            }
        } else {
            log.info {
                "[CACHE] 키 무효화 — cache=$cacheBaseName, key=$key " +
                    "(Redis Pub/Sub 미설정: 현재 Pod만 적용)"
            }
        }
    }
}
