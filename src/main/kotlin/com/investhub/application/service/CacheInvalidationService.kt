package com.investhub.application.service

import com.investhub.application.port.output.CacheEventPublisher
import com.investhub.application.port.output.CacheKeyEnumerable
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Service

/**
 * 어드민 캐시 목록 조회용 단건 정보.
 *
 * @param name       버전 해시를 포함한 실제 캐시 이름 (예: "recommendations:v7a0fe702").
 * @param baseName   버전 해시를 제거한 기본 이름 (evict 시 경로에 쓰는 값, 예: "recommendations").
 * @param entryCount 현재 보관된 엔트리 수.
 * @param keys       현재 보관된 엔트리 키 목록 (evict 가능한 실제 키 — 예: userId).
 */
data class CacheInfo(
    val name: String,
    val baseName: String,
    val entryCount: Int,
    val keys: List<String>,
)

/**
 * 캐시 무효화·재갱신 오케스트레이터.
 *
 * 단일 메서드 호출로:
 *   1. 로컬 Caffeine 캐시 즉시 무효화
 *   2. [CacheEventPublisher]로 이벤트 발행 (Redis Pub/Sub 연동 후 다른 Pod도 무효화)
 *   3. 옵션: [CacheRefreshStrategy]로 즉시 재갱신 (사용자 대기 없음)
 *
 * **캐시 이름 해석**: versioned 이름("recommendations:v7a0fe702")과
 * 기본 이름("recommendations") 모두 지원한다. 기본 이름으로 조회하면
 * 해당 이름으로 시작하는 모든 캐시(버전 포함)를 무효화한다.
 */
@Service
class CacheInvalidationService(
    private val cacheManager: CacheManager,
    private val publishers: List<CacheEventPublisher>,
    private val refreshStrategies: List<CacheRefreshStrategy>,
) {
    private val log = KotlinLogging.logger {}

    /**
     * 특정 키의 캐시를 무효화한다.
     *
     * @param cacheBaseName 기본 캐시 이름 (예: "recommendations")
     * @param key           무효화할 키 (예: userId)
     * @return 해석된 캐시 중 **하나라도 실제로 그 키를 보관하고 있었으면** true.
     *         (어드민 응답이 "evicted" vs "not_found"를 구분하는 근거.)
     */
    fun evict(
        cacheBaseName: String,
        key: String,
    ): Boolean {
        var removed = false
        resolveCache(cacheBaseName).forEach { cache ->
            val present = cache.evictIfPresent(key)
            if (present) removed = true
            log.info { "[CACHE] evict — cache=${cache.name}, key=$key, removed=$present" }
        }
        // 분산 전파는 멱등이므로, 이 Pod에 없었더라도 타 Pod를 위해 항상 발행한다.
        publishers.forEach { it.publishEviction(cacheBaseName, key) }
        return removed
    }

    /**
     * 캐시 전체를 무효화한다.
     *
     * @param cacheBaseName 기본 캐시 이름 (예: "recommendations")
     */
    fun evictAll(cacheBaseName: String) {
        resolveCache(cacheBaseName).forEach { cache ->
            cache.clear()
            log.info { "[CACHE] evictAll — cache=${cache.name}" }
        }
        publishers.forEach { it.publishEviction(cacheBaseName, null) }
    }

    /**
     * 특정 키의 캐시를 무효화한 뒤 즉시 재갱신한다.
     *
     * 단순 evict는 다음 요청이 들어올 때까지 캐시 미스가 발생한다.
     * refresh는 무효화 직후 데이터 소스를 호출해 캐시를 사전에 채운다.
     * 적합한 [CacheRefreshStrategy]가 없으면 evict만 수행한다.
     *
     * @param cacheBaseName 기본 캐시 이름 (예: "recommendations")
     * @param key           재갱신할 키 (예: userId)
     */
    fun evictAndRefresh(
        cacheBaseName: String,
        key: String,
    ) {
        evict(cacheBaseName, key)

        val strategy = refreshStrategies.find { it.supports(cacheBaseName) }
        if (strategy != null) {
            runCatching { strategy.refresh(cacheBaseName, key) }
                .onSuccess { log.info { "[CACHE] refresh 완료 — cache=$cacheBaseName, key=$key" } }
                .onFailure { ex -> log.warn(ex) { "[CACHE] refresh 실패 — cache=$cacheBaseName, key=$key (evict는 완료됨)" } }
        } else {
            log.debug { "[CACHE] refresh 전략 없음 — cache=$cacheBaseName (evict만 수행)" }
        }
    }

    /**
     * 전체 캐시를 무효화한 뒤 즉시 재갱신한다.
     *
     * @param cacheBaseName 기본 캐시 이름
     */
    fun evictAllAndRefresh(cacheBaseName: String) {
        evictAll(cacheBaseName)

        val strategy = refreshStrategies.find { it.supports(cacheBaseName) }
        if (strategy != null) {
            runCatching { strategy.refresh(cacheBaseName, null) }
                .onSuccess { log.info { "[CACHE] 전체 refresh 완료 — cache=$cacheBaseName" } }
                .onFailure { ex -> log.warn(ex) { "[CACHE] 전체 refresh 실패 — cache=$cacheBaseName (evict는 완료됨)" } }
        }
    }

    /** 현재 등록된 모든 캐시 이름을 반환한다 (기본 이름과 버전화된 이름 모두 포함). */
    fun listCacheNames(): List<String> = cacheManager.cacheNames.toList().sorted()

    /**
     * 등록된 캐시를 **엔트리 키까지 포함**해 반환한다.
     *
     * `listCacheNames()`가 캐시 "이름(컨테이너)"만 보여줘 운영자가 이름을 키로 오인하던 문제를 해소한다.
     * 키 열거를 지원하는 캐시([CacheKeyEnumerable], 예: TwoTierCache)는 실제 키 목록을,
     * 지원하지 않는 캐시는 빈 목록을 반환한다.
     */
    fun listCaches(): List<CacheInfo> =
        cacheManager.cacheNames.sorted().map { name ->
            val keys = (cacheManager.getCache(name) as? CacheKeyEnumerable)?.keys()?.sorted() ?: emptyList()
            CacheInfo(
                name = name,
                baseName = name.substringBefore(':'),
                entryCount = keys.size,
                keys = keys,
            )
        }

    private fun resolveCache(cacheBaseName: String): List<org.springframework.cache.Cache> =
        cacheManager.cacheNames
            .filter { it == cacheBaseName || it.startsWith("$cacheBaseName:") }
            .mapNotNull { cacheManager.getCache(it) }
            .also {
                if (it.isEmpty()) log.warn { "[CACHE] 캐시를 찾을 수 없음: $cacheBaseName" }
            }
}
