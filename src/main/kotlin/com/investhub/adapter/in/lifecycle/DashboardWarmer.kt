package com.investhub.adapter.`in`.lifecycle

import com.investhub.application.port.input.GetInvestmentDashboardUseCase
import com.investhub.application.service.Warmer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 대시보드 API 웜업 구현체.
 *
 * 웜업 효과:
 * 1. JIT 컴파일: 병렬 조회·Sealed Result 처리·JSON 직렬화 핫패스 컴파일
 * 2. 추천 캐시 적재: [CachingResilientAdapter]에 5분 TTL 캐시 사전 투입
 * 3. Resilience4j 초기화: CB 슬라이딩 윈도우에 초기 성공 샘플 투입
 *
 * 실패 허용: 웜업 실패가 서비스 전체를 막으면 안 된다.
 * [WarmupService]가 각 Warmer 실패를 독립적으로 처리한다.
 */
@Component
class DashboardWarmer(
    private val getDashboardUseCase: GetInvestmentDashboardUseCase,
    @Value("\${invest-hub.warmup.repeat-count:3}") private val repeatCount: Int,
) : Warmer {
    private val log = KotlinLogging.logger {}

    override fun name() = "DashboardWarmer"

    override fun warm() {
        log.info { "[WARMUP] 대시보드 ${repeatCount}회 사전 호출 시작" }
        repeat(repeatCount) { i ->
            runCatching {
                getDashboardUseCase.getDashboard("warmup-probe-$i")
            }.onSuccess {
                log.debug { "[WARMUP] 호출 #${i + 1} 성공" }
            }.onFailure { ex ->
                log.debug(ex) { "[WARMUP] 호출 #${i + 1} 실패 (Partial failure는 허용)" }
            }
        }
    }
}
