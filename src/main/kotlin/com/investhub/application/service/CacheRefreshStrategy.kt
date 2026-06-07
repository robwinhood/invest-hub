package com.investhub.application.service

/**
 * 캐시 재갱신 전략 포트.
 *
 * 단순 무효화(evict)는 캐시 미스 후 첫 요청이 들어올 때까지 기다린다.
 * 재갱신(refresh)은 무효화 직후 데이터 소스를 능동적으로 호출해
 * 캐시를 사전에 채운다 — 사용자가 첫 요청에서 지연을 경험하지 않는다.
 *
 * 구현체는 특정 캐시 이름을 처리할 수 있는지 선언하고,
 * [CacheInvalidationService]가 적합한 전략을 찾아 실행한다.
 */
interface CacheRefreshStrategy {
    /** 이 전략이 주어진 캐시 기본 이름을 처리할 수 있는지 반환한다. */
    fun supports(cacheBaseName: String): Boolean

    /**
     * 주어진 키의 캐시를 재갱신한다.
     *
     * @param key  재갱신할 대상 키 (예: userId). null이면 전체 재갱신 시도.
     */
    fun refresh(
        cacheBaseName: String,
        key: String?,
    )
}
