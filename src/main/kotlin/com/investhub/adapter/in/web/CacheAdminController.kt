package com.investhub.adapter.`in`.web

import com.investhub.application.service.CacheInvalidationService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 캐시 관리 API — 운영/어드민 전용.
 *
 * ML 모델 교체, 추천 데이터 긴급 수정 등의 상황에서
 * TTL 만료를 기다리지 않고 즉시 캐시를 무효화하거나 재갱신할 수 있다.
 *
 * **운영 예시:**
 * ```bash
 * # 특정 사용자의 추천 캐시 무효화
 * DELETE /admin/cache/recommendations/user-001
 *
 * # 특정 사용자의 추천 캐시 무효화 + 즉시 재갱신
 * POST /admin/cache/recommendations/user-001/refresh
 *
 * # 추천 캐시 전체 무효화
 * DELETE /admin/cache/recommendations
 *
 * # 현재 등록된 캐시 목록 조회
 * GET /admin/cache
 * ```
 *
 * 프로덕션에서는 이 엔드포인트를 API Gateway 레벨에서 IP 화이트리스트
 * 또는 별도 내부망으로 제한해야 한다.
 */
@RestController
@RequestMapping("/admin/cache")
class CacheAdminController(
    private val cacheInvalidationService: CacheInvalidationService,
) {
    private val log = KotlinLogging.logger {}

    /**
     * 현재 등록된 모든 캐시를 **엔트리 키까지 포함**해 반환한다.
     *
     * 각 항목의 `name`은 캐시 이름(버전 해시 포함), `keys`는 그 안에 실제로 들어 있는
     * evict 가능한 키 목록이다. (이름과 키를 분명히 구분해 오인을 방지한다.)
     */
    @GetMapping
    fun listCaches(): ResponseEntity<Map<String, Any>> {
        val caches = cacheInvalidationService.listCaches()
        return ResponseEntity.ok(
            mapOf(
                "caches" to caches,
                "count" to caches.size,
            ),
        )
    }

    /**
     * 특정 키의 캐시를 무효화한다.
     * 다음 요청이 들어올 때 데이터 소스에서 새로 조회한다.
     *
     * 응답 `status`: 실제로 지웠으면 `evicted`, 원래 그 키가 없었으면 `not_found`.
     * (키를 잘못 넣어도 무조건 `evicted`로 보여 혼동을 주던 문제를 해소한다.)
     */
    @DeleteMapping("/{cacheName}/{key}")
    fun evict(
        @PathVariable cacheName: String,
        @PathVariable key: String,
    ): ResponseEntity<Map<String, String>> {
        log.info { "[ADMIN] 캐시 무효화 요청: cache=$cacheName, key=$key" }
        val removed = cacheInvalidationService.evict(cacheName, key)
        return ResponseEntity.ok(
            mapOf(
                "status" to if (removed) "evicted" else "not_found",
                "cache" to cacheName,
                "key" to key,
            ),
        )
    }

    /**
     * 특정 키의 캐시를 무효화한 뒤 즉시 재갱신한다.
     * 사용자가 다음 요청에서 캐시 미스를 경험하지 않는다.
     */
    @PostMapping("/{cacheName}/{key}/refresh")
    fun evictAndRefresh(
        @PathVariable cacheName: String,
        @PathVariable key: String,
    ): ResponseEntity<Map<String, String>> {
        log.info { "[ADMIN] 캐시 무효화 + 재갱신 요청: cache=$cacheName, key=$key" }
        cacheInvalidationService.evictAndRefresh(cacheName, key)
        return ResponseEntity.ok(
            mapOf(
                "status" to "evicted_and_refreshed",
                "cache" to cacheName,
                "key" to key,
            ),
        )
    }

    /**
     * 캐시 전체를 무효화한다.
     * 다음 각 요청이 들어올 때 데이터 소스에서 새로 조회한다.
     */
    @DeleteMapping("/{cacheName}")
    fun evictAll(
        @PathVariable cacheName: String,
    ): ResponseEntity<Map<String, String>> {
        log.info { "[ADMIN] 캐시 전체 무효화 요청: cache=$cacheName" }
        cacheInvalidationService.evictAll(cacheName)
        return ResponseEntity.ok(
            mapOf(
                "status" to "evicted_all",
                "cache" to cacheName,
            ),
        )
    }

    /**
     * 캐시 전체를 무효화한 뒤 즉시 재갱신한다.
     * 재갱신 전략이 전체 재갱신을 지원하는 경우에만 실질적으로 동작한다.
     */
    @PostMapping("/{cacheName}/refresh")
    fun evictAllAndRefresh(
        @PathVariable cacheName: String,
    ): ResponseEntity<Map<String, String>> {
        log.info { "[ADMIN] 캐시 전체 무효화 + 재갱신 요청: cache=$cacheName" }
        cacheInvalidationService.evictAllAndRefresh(cacheName)
        return ResponseEntity.ok(
            mapOf(
                "status" to "evicted_all_and_refreshed",
                "cache" to cacheName,
            ),
        )
    }
}
