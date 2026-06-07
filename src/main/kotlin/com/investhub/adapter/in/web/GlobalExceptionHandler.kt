package com.investhub.adapter.`in`.web

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.resilience4j.bulkhead.BulkheadFullException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 컨트롤러 레이어의 예외를 RFC 7807 Problem Details 형식으로 변환한다.
 *
 * 비즈니스 실패(데이터 소스 조회 실패)는 Sealed Result가 처리한다.
 * 이 핸들러는 컨트롤러 레이어까지 전파된 기술적 예외를 담당한다.
 *
 * 100K TPS 환경에서 올바른 HTTP 상태 코드:
 * - Rate Limit 초과 → 429 (클라이언트가 backoff 후 재시도하도록)
 * - Bulkhead 초과   → 429 (서버 리소스 부족 — 재시도 가능)
 * - CB OPEN         → 503 (서비스 일시 불가 — 재시도 권장)
 * - 예상치 못한 오류 → 500
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = KotlinLogging.logger {}

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(ex: MissingRequestHeaderException): ProblemDetail {
        log.warn { "필수 헤더 누락: ${ex.headerName}" }
        return ProblemDetail
            .forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "필수 헤더가 없습니다: '${ex.headerName}'",
            ).apply {
                title = "Required Header Missing"
            }
    }

    /** Resilience4j Rate Limiter 초과 — 컨트롤러에 직접 적용된 경우 */
    @ExceptionHandler(RequestNotPermitted::class)
    fun handleRateLimit(ex: RequestNotPermitted): ProblemDetail {
        log.warn { "Rate Limit 초과: ${ex.message}" }
        return ProblemDetail
            .forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
            ).apply {
                title = "Too Many Requests"
            }
    }

    /** Resilience4j Bulkhead 초과 — 서버 동시 처리 한도 초과 */
    @ExceptionHandler(BulkheadFullException::class)
    fun handleBulkheadFull(ex: BulkheadFullException): ProblemDetail {
        log.warn { "Bulkhead 초과: ${ex.message}" }
        return ProblemDetail
            .forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "서버 리소스가 부족합니다. 잠시 후 다시 시도해주세요.",
            ).apply {
                title = "Resource Exhausted"
            }
    }

    /** Resilience4j Circuit Breaker OPEN — 서비스 일시 불가 */
    @ExceptionHandler(CallNotPermittedException::class)
    fun handleCircuitBreakerOpen(ex: CallNotPermittedException): ProblemDetail {
        log.warn { "Circuit Breaker OPEN: ${ex.message}" }
        return ProblemDetail
            .forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "일부 서비스가 일시적으로 이용 불가합니다.",
            ).apply {
                title = "Service Unavailable"
            }
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ProblemDetail {
        log.error(ex) { "처리되지 않은 예외 발생" }
        return ProblemDetail
            .forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
            ).apply {
                title = "Internal Server Error"
            }
    }
}
