package com.investhub.adapter.out.cache

import com.investhub.application.port.output.DistributedCacheStore
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cache.Cache
import org.springframework.cache.support.SimpleValueWrapper
import java.util.concurrent.Callable

/**
 * L1(로컬 Caffeine) + L2(분산 Mock Redis) 2계층 캐시.
 *
 * `org.springframework.cache.Cache`를 구현하므로 [com.investhub.adapter.out.CachingResilientAdapter]와
 * `CacheManager` 기반 무효화 서비스가 **변경 없이** 그대로 동작한다.
 *
 * 조회 경로:  L1 hit → 반환 / L1 miss → L2 조회 → hit 시 L1 적재 후 반환 / 둘 다 miss → null(원본 호출 유도)
 * 저장(put): L1 + L2 동시 write-through.
 * 무효화(evict/clear): **L1 + L2 모두** 제거. (분산 전파는 Pub/Sub 발행자가 담당)
 *
 * L2에는 직렬화된 바이트가 저장된다(실제 Redis와 동일). 직렬화/역직렬화 로직은
 * 값 타입을 아는 [com.investhub.config.CacheConfig]에서 주입받아 이 클래스는 타입에 비의존적이다.
 *
 * @param cacheName  캐시 이름(버전 해시 포함). L2 키 네임스페이스로도 사용된다.
 * @param l1         로컬 1차 캐시 (Caffeine).
 * @param l2         분산 2차 캐시 (Mock Redis).
 * @param ttlSeconds L2 TTL(초).
 * @param serialize  값 → 바이트.
 * @param deserialize 바이트 → 값.
 */
class TwoTierCache(
    private val cacheName: String,
    private val l1: Cache,
    private val l2: DistributedCacheStore,
    private val ttlSeconds: Long,
    private val serialize: (Any) -> ByteArray,
    private val deserialize: (ByteArray) -> Any,
) : Cache {
    private val log = KotlinLogging.logger {}

    override fun getName(): String = cacheName

    override fun getNativeCache(): Any = l1.nativeCache

    private fun l2Key(key: Any): String = "$cacheName::$key"

    override fun get(key: Any): Cache.ValueWrapper? {
        // 1) L1 조회
        l1.get(key)?.let {
            log.debug { "[CACHE] L1 hit — cache=$cacheName, key=$key" }
            return it
        }
        // 2) L2 조회 → hit 시 L1 승격(promotion)
        val bytes = l2.get(l2Key(key))
        if (bytes != null) {
            val value = deserialize(bytes)
            l1.put(key, value)
            log.debug { "[CACHE] L2 hit → L1 승격 — cache=$cacheName, key=$key" }
            return SimpleValueWrapper(value)
        }
        log.debug { "[CACHE] miss(L1+L2) — cache=$cacheName, key=$key" }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(
        key: Any,
        type: Class<T>?,
    ): T? {
        val value = get(key)?.get() ?: return null
        require(type == null || type.isInstance(value)) {
            "캐시 값 타입 불일치: cache=$cacheName, key=$key, expected=$type"
        }
        return value as T
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(
        key: Any,
        valueLoader: Callable<T>,
    ): T? {
        get(key)?.let { return it.get() as T }
        return try {
            val loaded = valueLoader.call()
            put(key, loaded)
            loaded
        } catch (ex: Exception) {
            throw Cache.ValueRetrievalException(key, valueLoader, ex)
        }
    }

    override fun put(
        key: Any,
        value: Any?,
    ) {
        if (value == null) {
            evict(key)
            return
        }
        l1.put(key, value)
        l2.put(l2Key(key), serialize(value), ttlSeconds)
        log.debug { "[CACHE] put(L1+L2) — cache=$cacheName, key=$key" }
    }

    override fun evict(key: Any) {
        l1.evict(key)
        l2.evict(l2Key(key))
        log.debug { "[CACHE] evict(L1+L2) — cache=$cacheName, key=$key" }
    }

    override fun clear() {
        l1.clear()
        l2.evictByPrefix("$cacheName::")
        log.debug { "[CACHE] clear(L1+L2) — cache=$cacheName" }
    }

    // ── 분산 무효화 전파용: 다른 Pod가 받았을 때 L1만 비운다 (L2는 공유라 1회 evict로 충분) ──

    /** Pub/Sub 수신 시 호출 — 이 Pod의 L1만 제거한다. */
    fun evictLocalOnly(key: Any) {
        l1.evict(key)
        log.debug { "[CACHE] L1-only evict(분산 전파) — cache=$cacheName, key=$key" }
    }

    /** Pub/Sub 수신 시 호출 — 이 Pod의 L1 전체만 제거한다. */
    fun clearLocalOnly() {
        l1.clear()
        log.debug { "[CACHE] L1-only clear(분산 전파) — cache=$cacheName" }
    }
}
