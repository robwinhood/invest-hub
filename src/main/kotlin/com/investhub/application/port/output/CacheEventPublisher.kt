package com.investhub.application.port.output

/**
 * 캐시 무효화 이벤트 발행 포트.
 *
 * 현재: [NoOpCacheEventPublisher] — 로그만 출력 (Redis 없음)
 * 향후: RedisCacheEventPublisher — Redis Pub/Sub으로 전체 Pod 동기화
 *
 * 이 인터페이스를 통해 애플리케이션 코어는 발행 구현에 의존하지 않는다.
 * Redis 추가 시 이 구현체만 교체하면 된다.
 */
interface CacheEventPublisher {
    /**
     * 캐시 무효화 이벤트를 발행한다.
     *
     * @param cacheBaseName 기본 캐시 이름 (예: "recommendations")
     * @param key           무효화할 키. null이면 전체 무효화.
     */
    fun publishEviction(
        cacheBaseName: String,
        key: String?,
    )
}
