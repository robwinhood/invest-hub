package com.investhub.adapter.out.cache

import com.investhub.application.port.output.DistributedCacheStore
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 인메모리 Mock Redis (L2 분산 캐시 + Pub/Sub).
 *
 * 외부 프로세스·라이브러리 없이 Redis의 핵심 동작을 시뮬레이션한다.
 * 과제 제약("외부 시스템은 모두 Mock") + GA 전용 정책(embedded-redis 등 비-GA 회피)에 부합한다.
 *
 * 재현하는 Redis 특성:
 * - **공유 저장소**: 단일 인스턴스가 모든 Pod에서 공유되는 L2를 모사한다.
 * - **직렬화 저장**: 값을 바이트로 보관해 실제 Redis와 동일하게 동작한다.
 * - **TTL**: 키별 만료 시각을 두고 조회 시 lazy expire 한다.
 * - **Pub/Sub**: 채널 발행 시 구독자에게 동기 전달한다(분산 무효화 모사).
 *
 * 실 환경 전환: Lettuce 기반 `RedisDistributedCacheStore`로 교체하면 되고,
 * [DistributedCacheStore]를 구현하므로 [TwoTierCache] 등 상위 코드는 변경 불필요.
 */
@Component
class MockRedisStore : DistributedCacheStore {
    private val log = KotlinLogging.logger {}

    private data class Entry(
        val value: ByteArray,
        val expireAtMillis: Long,
    )

    private val store = ConcurrentHashMap<String, Entry>()
    private val channels = ConcurrentHashMap<String, CopyOnWriteArrayList<(String) -> Unit>>()

    override fun get(key: String): ByteArray? {
        val entry = store[key] ?: return null
        if (System.currentTimeMillis() > entry.expireAtMillis) {
            store.remove(key)
            log.debug { "[L2/MockRedis] TTL 만료 — key=$key" }
            return null
        }
        return entry.value
    }

    override fun put(
        key: String,
        value: ByteArray,
        ttlSeconds: Long,
    ) {
        store[key] = Entry(value, System.currentTimeMillis() + ttlSeconds * 1000)
        log.debug { "[L2/MockRedis] put — key=$key, ttl=${ttlSeconds}s, bytes=${value.size}" }
    }

    override fun evict(key: String) {
        if (store.remove(key) != null) log.debug { "[L2/MockRedis] evict — key=$key" }
    }

    override fun evictIfPresent(key: String): Boolean {
        val removed = store.remove(key)
        // 보관돼 있었더라도 TTL이 이미 지났다면 사실상 부재로 간주한다.
        val present = removed != null && System.currentTimeMillis() <= removed.expireAtMillis
        if (present) log.debug { "[L2/MockRedis] evictIfPresent — key=$key (removed)" }
        return present
    }

    override fun evictByPrefix(prefix: String) {
        val removed = store.keys.filter { it.startsWith(prefix) }
        removed.forEach { store.remove(it) }
        if (removed.isNotEmpty()) log.debug { "[L2/MockRedis] evictByPrefix — prefix=$prefix, removed=${removed.size}" }
    }

    override fun keysByPrefix(prefix: String): Set<String> {
        val now = System.currentTimeMillis()
        return store
            .filter { (key, entry) -> key.startsWith(prefix) && now <= entry.expireAtMillis }
            .keys
            .toSet()
    }

    override fun size(): Int = store.size

    // ── Pub/Sub (분산 무효화 모사) ──────────────────────────────

    /** 채널에 메시지를 발행한다. 구독자에게 동기 전달한다(Mock). */
    fun publish(
        channel: String,
        message: String,
    ) {
        val subscribers = channels[channel] ?: return
        subscribers.forEach { listener ->
            runCatching { listener(message) }
                .onFailure { ex -> log.warn(ex) { "[L2/MockRedis] 구독자 처리 실패 — channel=$channel" } }
        }
    }

    /** 채널을 구독한다. 발행 시 listener가 호출된다. */
    fun subscribe(
        channel: String,
        listener: (String) -> Unit,
    ) {
        channels.computeIfAbsent(channel) { CopyOnWriteArrayList() }.add(listener)
        log.info { "[L2/MockRedis] 구독 등록 — channel=$channel" }
    }
}
