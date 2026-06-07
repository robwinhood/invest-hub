package com.investhub.adapter.`in`.web.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 요청 진입 시점에 MDC에 userId와 requestId를 설정한다.
 *
 * 이후 서비스·어댑터 레이어의 모든 로그에 이 두 필드가 자동으로 포함되므로
 * 분산 로그에서 특정 사용자 요청 전체를 하나의 requestId로 추적할 수 있다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class MdcFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val userId = request.getHeader(HEADER_USER_ID) ?: "anonymous"
        val requestId = UUID.randomUUID().toString()

        MDC.put(MDC_USER_ID, userId)
        MDC.put(MDC_REQUEST_ID, requestId)
        response.setHeader("X-Request-Id", requestId)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.clear()
        }
    }

    companion object {
        const val MDC_USER_ID = "userId"
        const val MDC_REQUEST_ID = "requestId"
        const val HEADER_USER_ID = "X-User-Id"
    }
}
