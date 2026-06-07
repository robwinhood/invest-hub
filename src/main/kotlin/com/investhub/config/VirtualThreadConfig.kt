package com.investhub.config

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 어댑터별 독립 Virtual Thread ExecutorService 구성.
 *
 * 단일 공유 executor를 사용하면 제휴사 API 지연이 내부 계좌 조회에도 영향을 준다.
 * 어댑터마다 별도 executor를 사용해 장애를 격리한다.
 *
 * 스레드 이름 규칙: {역할}-vt-{순번}
 *   account-vt-0, partner-vt-1, recommendation-vt-2, service-vt-0
 *   → 로그/APM/스레드 덤프에서 어느 어댑터 문제인지 즉시 식별 가능
 *
 * 모니터링:
 *   각 executor가 MeterRegistry에 등록되어 Micrometer/Datadog으로 지표를 수집한다.
 *   /actuator/metrics/executor.* 엔드포인트로 실시간 조회 가능.
 *   Resilience4j Bulkhead가 어댑터별 동시 접근 수를 추가로 제어한다.
 */
@Configuration
class VirtualThreadConfig {
    /**
     * 내부 원장 전용 executor.
     * 가장 신뢰성 높은 내부 시스템 — 제휴사 장애로부터 완전히 격리된다.
     */
    @Bean("accountExecutor")
    @Qualifier("accountExecutor")
    fun accountExecutor(meterRegistry: MeterRegistry): ExecutorService =
        ExecutorServiceMetrics.monitor(
            meterRegistry,
            Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("account-vt-", 0).factory(),
            ),
            "account",
            listOf(),
        )

    /**
     * 제휴사 해외 주식 전용 executor.
     * 가장 불안정한 외부 시스템 — 지연·오류가 발생해도 다른 executor에 영향 없음.
     */
    @Bean("partnerExecutor")
    @Qualifier("partnerExecutor")
    fun partnerExecutor(meterRegistry: MeterRegistry): ExecutorService =
        ExecutorServiceMetrics.monitor(
            meterRegistry,
            Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("partner-vt-", 0).factory(),
            ),
            "partner",
            listOf(),
        )

    /**
     * 추천 엔진 전용 executor.
     * 비필수 데이터 — 느려져도 계좌·주식 조회에 영향 없음.
     */
    @Bean("recommendationExecutor")
    @Qualifier("recommendationExecutor")
    fun recommendationExecutor(meterRegistry: MeterRegistry): ExecutorService =
        ExecutorServiceMetrics.monitor(
            meterRegistry,
            Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("recommendation-vt-", 0).factory(),
            ),
            "recommendation",
            listOf(),
        )

    /**
     * 서비스 레이어 병렬 조회 전용 executor.
     * InvestmentDashboardService에서 세 Future를 제출하는 용도.
     */
    @Bean("serviceExecutor")
    @Qualifier("serviceExecutor")
    fun serviceExecutor(meterRegistry: MeterRegistry): ExecutorService =
        ExecutorServiceMetrics.monitor(
            meterRegistry,
            Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("service-vt-", 0).factory(),
            ),
            "service",
            listOf(),
        )
}
