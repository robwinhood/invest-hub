package com.investhub.adapter.`in`.lifecycle

import com.investhub.application.port.input.GetInvestmentDashboardUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * 로컬 구동 전용 캐시 시더 — `local` 프로파일에서만 활성화된다.
 *
 * `./gradlew bootRun`은 `local` 프로파일로 뜨도록 구성돼 있어,
 * 로컬에서 서버를 띄우면 **추천 캐시가 데모 사용자로 미리 채워진 상태**로 시작한다.
 * 덕분에 기동 직후 `GET /admin/cache`가 실제 evict 가능한 키(`user-001` 등)를 보여주고,
 * `DELETE /admin/cache/recommendations/user-001` 같은 명령을 바로 시험해볼 수 있다.
 *
 * 시딩 방식: [GetInvestmentDashboardUseCase]를 데모 사용자별로 1회 호출한다
 * ([DashboardWarmer]와 동일 경로). [com.investhub.adapter.out.CachingResilientAdapter]가
 * 추천 응답을 캐시에 자동 저장하므로 별도 캐시 적재 로직은 불필요하다.
 *
 * Swagger 탐색기(docs/api/)는 백엔드가 없는 브라우저 목이라 "로컬 구동" 개념이 없으며,
 * 이 시더와 무관하게 정적 Mock 응답을 그대로 사용한다.
 *
 * 시딩은 다른 `ApplicationReadyEvent` 리스너(웜업 등) 순서에 의존하지 않고 독립적으로 동작한다.
 * 로그 가독성을 위해 [Order]를 가장 낮은 우선순위로 둬 대체로 마지막에 실행되게 한다.
 */
@Component
@Profile("local")
@Order(Ordered.LOWEST_PRECEDENCE)
class LocalCacheSeeder(
    private val getDashboardUseCase: GetInvestmentDashboardUseCase,
    @Value("\${invest-hub.local-seed.users:user-001,user-002,user-003}") private val demoUsers: List<String>,
) : ApplicationListener<ApplicationReadyEvent> {
    private val log = KotlinLogging.logger {}

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        log.info { "[LOCAL-SEED] 데모 사용자 캐시 사전 적재 시작: $demoUsers" }
        demoUsers.forEach { userId ->
            runCatching { getDashboardUseCase.getDashboard(userId) }
                .onSuccess { log.info { "[LOCAL-SEED] 캐시 적재 완료 — userId=$userId" } }
                .onFailure { ex -> log.warn(ex) { "[LOCAL-SEED] 캐시 적재 실패 — userId=$userId (무시)" } }
        }
    }
}
