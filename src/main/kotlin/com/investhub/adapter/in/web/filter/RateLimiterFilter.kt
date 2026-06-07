package com.investhub.adapter.`in`.web.filter

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

// 글로벌 Rate Limiter 필터.
// 100K TPS 버스트 시 즉시 429를 반환해 서비스 레이어 도달 전에 차단한다.
// health 및 actuator 경로는 Rate Limit 적용 제외.
// 설정: application.yml resilience4j.ratelimiter.instances.dashboard
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class RateLimiterFilter(
    rateLimiterRegistry: RateLimiterRegistry,
) : OncePerRequestFilter() {
    private val log = KotlinLogging.logger {}
    private val rateLimiter = rateLimiterRegistry.rateLimiter("dashboard")

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        return uri.startsWith("/health") || uri.startsWith("/actuator")
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (rateLimiter.acquirePermission()) {
            filterChain.doFilter(request, response)
            return
        }

        log.warn { "Rate Limit exceeded: ${request.requestURI}" }
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(
            "{\"status\":429,\"title\":\"Too Many Requests\",\"detail\":\"Too many requests. Please try again later.\"}",
        )
    }
}
