package com.investhub.adapter.`in`.web

import com.investhub.domain.dashboard.FailureReason
import org.springframework.http.HttpStatus

/**
 * 도메인 단독 엔드포인트에서 [FailureReason] → HTTP 상태 매핑.
 *
 * 집계 엔드포인트(`/dashboard`)는 부분 실패를 200 + 섹션 `status=FAILURE`로 표현하지만,
 * 도메인 단독 리소스 엔드포인트는 REST 관례에 따라 **실패 원인에 맞는 상태 코드**를 돌려준다.
 * (클라이언트가 backoff·재시도 여부를 상태 코드만으로 판단할 수 있도록)
 */
fun FailureReason.toHttpStatus(): HttpStatus =
    when (this) {
        FailureReason.CIRCUIT_OPEN, FailureReason.SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE // 503 재시도 권장
        FailureReason.TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT // 504 상류 지연
        FailureReason.RESOURCE_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS // 429 backoff 후 재시도
    }
