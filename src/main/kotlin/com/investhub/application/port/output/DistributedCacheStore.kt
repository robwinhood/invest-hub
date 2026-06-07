package com.investhub.application.port.output

/**
 * L2(분산) 캐시 저장소 포트.
 *
 * 모든 Pod가 공유하는 영속 계층(실 환경: Redis)을 추상화한다.
 * 값은 **직렬화된 바이트**로 저장된다 — 실제 Redis와 동일하게 동작시켜
 * 클래스 구조 변경 시 역직렬화 충돌이 발생할 수 있는 환경을 그대로 재현한다.
 * (그래서 [com.investhub.config.CacheKeyVersionGenerator]의 키 버전 관리가 실제 의미를 갖는다.)
 *
 * 현재 구현: [com.investhub.adapter.out.cache.MockRedisStore] (인메모리 Mock).
 * 실 환경 전환 시: Lettuce/Jedis 기반 `RedisDistributedCacheStore`로 교체하고
 * 애플리케이션 코어는 변경하지 않는다.
 */
interface DistributedCacheStore {
    /** 직렬화된 값을 조회한다. 없거나 TTL이 만료됐으면 null. */
    fun get(key: String): ByteArray?

    /** 직렬화된 값을 TTL(초)과 함께 저장한다. */
    fun put(
        key: String,
        value: ByteArray,
        ttlSeconds: Long,
    )

    /** 단일 키를 제거한다. */
    fun evict(key: String)

    /**
     * 단일 키를 제거하고, **실제로 제거되었는지** 반환한다.
     * (키가 없었거나 TTL이 만료된 상태였으면 false — 어드민 evict 응답의 정확도용.)
     */
    fun evictIfPresent(key: String): Boolean

    /** prefix로 시작하는 모든 키를 제거한다 (캐시 전체 무효화용). */
    fun evictByPrefix(prefix: String)

    /**
     * prefix로 시작하는(만료되지 않은) 키 목록을 반환한다.
     * 실제 Redis의 `SCAN`/`KEYS prefix*`에 대응하는 운영 조회용 연산이다.
     */
    fun keysByPrefix(prefix: String): Set<String>

    /** 현재 보관 중인 엔트리 수 (관측/테스트용). */
    fun size(): Int
}
