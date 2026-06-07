# CHANGELOG

모든 주요 변경사항을 이 파일에 기록한다.  
형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.0.0/)를 따른다.

---

## [Unreleased]

### Added
- **이중 캐시 (L1 Caffeine + L2 Mock Redis) 도입** — 추천 상품 캐시를 2계층으로 전환.
  - `TwoTierCache` (`org.springframework.cache.Cache` 구현) — L1 hit→반환 / L1 miss·L2 hit→L1 승격 / 둘 다 miss→원본 호출 후 L1·L2 write-through. `CachingResilientAdapter`·무효화 서비스는 변경 없이 동작.
  - `DistributedCacheStore` 포트 + `MockRedisStore` (인메모리 Mock Redis: KV + TTL + Pub/Sub). 외부 의존성 없이 Redis 모사(GA 정책상 embedded-redis 회피). 실 환경은 Lettuce 구현으로 포트만 교체.
  - L2는 Jackson 3로 **직렬화 저장** → `CacheKeyVersionGenerator`의 키 버전이 실효를 갖게 됨(직렬화 충돌 방지).
  - 무효화가 **L1·L2 모두** 제거 + `MockRedisCacheEventPublisher`(빈 `redisCacheEventPublisher`) → `L1EvictionSubscriber` Pub/Sub으로 타 Pod L1 전파. `NoOpCacheEventPublisher`는 `@ConditionalOnMissingBean`으로 자동 비활성화.
  - 의존성: `tools.jackson.module:jackson-module-kotlin` 추가 (Spring Boot 4 관리 jackson-bom 3.1.2, GA).
  - 테스트 `TwoTierCacheTest`(8) 추가 — 총 **110개**.

### Fixed
- PR 템플릿의 "AI 자동 단계 완료 확인(워크플로우 ①~④)" 체크리스트에 누락돼 있던 **②(설계) 항목 추가**. 헤더는 "①~④"로 명시하면서 정작 ②만 빠져 있어 `docs/ai-dev-workflow.md`의 5단계 정의와 불일치하던 문제 정정.
- **CI 파이프라인 복구**: `.github/workflows/ci.yml`이 존재하지 않는 `detektMain`/`detektTest` 태스크를 호출해 lint 잡이 항상 실패하던 문제 수정 (Detekt 미채택 결정과 불일치). `lintKotlin` → `test`(Kotest + ArchUnit) 구조로 정정, 잘못된 테스트 수 표기("71개") 제거.
- 문서 전반의 테스트 수 표기 정합성 정정 (README·HELP·project-summary의 "101개" → 실제 수치 동기화. `HexagonalArchitectureTest`는 ArchUnit 규칙 6개). 이중 캐시 도입 후 현재 총 **110개**.
- `docs/project-summary.md` Q3의 "Detekt가 코루틴을 차단한다" 오기재를 **ArchUnit `noCoroutineUsage`** 로 정정 (Detekt는 미채택).
- `HELP.md`의 Spring Boot 4.1 참조 링크를 **4.0** 으로 정정 (실제 채택 버전 일치).

### Changed
- **README에 핵심 설계 의사결정 섹션 추가**: (1) 잠재적 위험 분석(R1–R8) (2) 리스크별 아키텍처 의사결정·대책 (3) 성능·자원 최적화 (4) 신뢰성 검증 결과(시나리오↔테스트 매핑)를 README 상단에 정리.
- 코드 주석·문서의 내부 프로젝트/티켓 참조를 일반화 (`StartupReadyTracker`, `CacheKeyVersionGenerator`, ADR-007).
- `docs/project-summary.md`의 서술 톤을 프로젝트 리뷰 문서에 맞게 정리.

### Added
- AI 개발 워크플로우 5단계 도입 (PRD → 설계 → 개발+테스트 → 비판적 검토 → 사람 리뷰)
  - `docs/ai-dev-workflow.md` — 전체 프로세스·단계별 자동화 장치 명문화
  - `.claude/commands/self-review.md` — 비판적 자가 검토 skill (④단계)
  - `/add-datasource` skill에 ④ 자가 검토 단계 통합
  - PR 템플릿에 "AI 자동 단계 완료 + 자가 검토 보고서" 섹션 추가
- `/ship` skill (`.claude/commands/ship.md`) — 워크플로우 ④~⑤ 자동화. `self-review` → `check-all` → commit → push → `gh pr create`를 한 번에 오케스트레이션해 PR 생성까지 무인 수행(머지는 기본 사람 몫). 보호 브랜치·`gh` 미인증·`check-all` 실패 시 중단하는 가드레일 포함.
- 캐시 자동 키 버전 관리 (`CacheKeyVersionGenerator`)
  - 클래스 구조(필드명+타입) SHA-256 해시를 캐시 이름에 자동 삽입
  - `InvestmentProduct` 필드 변경 시 캐시 이름 자동 교체 — 사람이 버전 올릴 필요 없음
  - `recommendations:v7a0fe702` 형태 (Redis 도입 시에도 동일 메커니즘)
- 캐시 무효화·재갱신 인프라 (`CacheInvalidationService`, `CacheEventPublisher`, `CacheRefreshStrategy`)
  - `evict(cacheName, key)` — 특정 키 즉시 무효화
  - `evictAll(cacheName)` — 전체 무효화
  - `evictAndRefresh(cacheName, key)` — 무효화 + 즉시 재갱신 (캐시 미스 없음)
  - `NoOpCacheEventPublisher` — Redis Pub/Sub 연동 전 로그 출력 (인터페이스 준비 완료)
  - `RecommendationCacheRefreshStrategy` — 추천 캐시 재갱신 전략 구현
- 캐시 관리 Admin API (`CacheAdminController`)
  - `GET /admin/cache` — 캐시 목록 조회
  - `DELETE /admin/cache/{name}/{key}` — 특정 키 무효화
  - `DELETE /admin/cache/{name}` — 전체 무효화
  - `POST /admin/cache/{name}/{key}/refresh` — 무효화 + 즉시 재갱신
  - `POST /admin/cache/{name}/refresh` — 전체 무효화 + 재갱신
- 테스트 보강: `CacheKeyVersionGeneratorTest`(16), `CacheInvalidationServiceTest`(9), `CacheAdminControllerTest`(5) 추가
- 버전 업그레이드
  - Spring Boot 4.0.1 → **4.0.6** (GA — 비-GA 안전 원칙에 따라 4.1.0-RC1 대신 채택)
  - Kotlin 2.3.0 → **2.3.21** (GA)
  - Resilience4j 2.3.0 → **2.4.0** (GA), `resilience4j-spring-boot3` → **`resilience4j-spring-boot4`** (Spring Boot 4.x 필수)
  - MockK 1.13.17 → **1.14.11** (GA)
  - ArchUnit 1.3.0 → **1.4.2** (GA, Java 21 bytecode 지원 개선)
  - Kotest 5.9.1 → **6.1.11** (GA; `kotest-extensions-spring` 비호환으로 제거, `@ExtendWith(SpringExtension::class)` 전환)
  - kotlin-logging 7.0.3 → **8.0.4** (GA)
- 코드 품질 도구 (다중 개발자/AI 환경, 전부 GA)
  - `kotlinter 5.0.1` — Ktlint 1.5 포맷 강제 (`formatKotlin` / `lintKotlin`)
  - `.editorconfig` — IDE·CI 일관 포맷 규칙
  - ArchUnit에 **코루틴 금지 규칙**(`noCoroutineUsage`) 추가 — `kotlinx.coroutines` import 차단 (ADR-002 강제)
  - `check-all` — `lintKotlin + test` (파일 수정 없음, PR 전 필수)
  - `fix-all` — `formatKotlin + test` (포맷 자동 수정)
  - `.github/workflows/ci.yml` — lint(Ktlint) → test 2-job 구조

### Changed
- **의존성 정책: GA(정식 릴리즈) 전용으로 확정.** RC·alpha·beta·SNAPSHOT 도입 금지를 `CLAUDE.md` 절대 금지 사항과 README에 명시.
- Detekt **미채택 결정**: GA(1.23.8)는 Kotlin 2.3.21 비호환, Kotlin 2.3.21 지원 버전은 alpha뿐. 정적 분석 역할을 Ktlint(포맷)+ArchUnit(아키텍처·코루틴 금지)으로 분담. (관련 `detekt.yml`, `detekt-test.yml` 삭제)
- ADR-007 추가 — 캐시 키 자동 버전 + 무효화·재갱신 설계

### High Availability
- 고가용성(100K TPS) 대응
  - `application.yml` — HTTP/2, GZIP 압축, Tomcat 연결 10K, 관리 포트 8081 분리
  - Rate Limiter — 인스턴스당 15K TPS 상한, 초과 시 즉시 429 반환 (`RateLimiterFilter`)
  - Resilience4j `configs.default` — 새 어댑터가 portName만 선언해도 CB 자동 적용 (휴먼 에러 방지)
  - CB 슬라이딩 윈도우 10→100, Bulkhead 50→4,000 (100K TPS 수치 재산정)
- 웜업 & Startup Probe (신규 Pod cold-start 및 K8s readiness gap 대응)
  - `WarmupService` / `Warmer` 인터페이스 / `DashboardWarmer` — 기동 시 JIT+캐시 사전 적재
  - `StartupReadyTracker` — 웜업→K8s Probe gap 메트릭 (NaN sentinel, Datadog 오염 방지)
  - `WarmupInvoker` — ApplicationReadyEvent 수신 시 자동 실행
  - `HealthController` — `/health/startup`, `/health/ready`, `/health/live` 전용 엔드포인트
- ArchUnit `outAdaptersMustExtendResilientAdapter` 규칙 — CB 누락 어댑터 빌드 차단
- `GlobalExceptionHandler` — 429(RateLimiter), 429(Bulkhead), 503(CB OPEN) 추가
- 웜업 테스트 (`WarmupServiceTest`, `DashboardWarmerTest`, `StartupReadyTrackerTest`, `HealthControllerTest`)
- 어댑터별 독립 Virtual Thread Executor 분리 (장애 격리 + 스레드 명명)
  - `accountExecutor` / `partnerExecutor` / `recommendationExecutor` / `serviceExecutor`
  - 스레드 이름 규칙: `{역할}-vt-N` (로그/APM에서 역할 즉시 식별 가능)
  - Micrometer ExecutorServiceMetrics 등록으로 `executor.*` 메트릭 노출
- `VirtualThreadIsolationTest` — executor 격리·이름·Virtual Thread·병렬성 검증 (13개 테스트)
- `.github/workflows/ci.yml` — GitHub Actions CI 파이프라인 (push/PR 시 자동 테스트)
- `.github/pull_request_template.md` — PR 리뷰 체크리스트
- `GlobalExceptionHandler` — RFC 7807 Problem Details 형식 일관된 에러 응답
- `GlobalExceptionHandlerTest` — 에러 응답 형식 검증 (33번째 테스트)
- `CHANGELOG.md` — 변경 이력 관리
- `docs/ai-dev-guide.md` — AI 자동 개발·운영 활용 가이드
- `docs/prd-datasource-template.md` — skill 사용법 포함 상세 PRD 가이드

### Changed
- `application.yml` — Graceful Shutdown 설정 추가 (`server.shutdown: graceful`)
- `CLAUDE.md` — CI/PR 프로세스, 에러 처리 규칙 추가

---

## [0.2.0] — 2026-05-31

### Added
- `CLAUDE.md` — AI 세션 간 컨텍스트 유지를 위한 가이드 문서
- `docs/adr/` — 설계 결정 기록 6개 (ADR-001 ~ ADR-006)
- `.claude/commands/add-datasource.md` — 새 데이터 소스 자동 생성 skill
- `docs/prd-datasource-template.md` — PRD 작성 가이드 및 템플릿
- `CachingResilientAdapter<T>` — 저실시간 어댑터의 캐시 구조적 보장
- `CacheConfig` — Caffeine 캐시 (추천 상품 5분 TTL)
- `HexagonalArchitectureTest` — ArchUnit 기반 아키텍처 경계 강제 (4개 규칙)
- `CachingResilientAdapterTest` — 캐시 히트/미스 검증 (4개 테스트)
- `MdcFilter` — 요청 진입 시 userId/requestId MDC 등록, X-Request-Id 응답 헤더
- `SectionStatus` enum — 응답 status 필드 타입 안전화 (문자열 하드코딩 제거)
- 도메인 모델 `init { require(...) }` 불변식 검증 추가

### Changed
- `InvestmentDashboardService` — `supplyAsyncWithMdc()` 래퍼로 Virtual Thread MDC 전파 수정
- `ResilientAdapter` — 어댑터 3개의 CB+Bulkhead+TL 보일러플레이트를 추상 클래스로 통합
- `RecommendationEngineAdapter` — `CachingResilientAdapter` 상속으로 캐시 자동 적용
- `application.yml` — MDC 포함 로깅 패턴 추가

---

## [0.1.0] — 2026-05-31

### Added
- 투자 대시보드 API 최초 구현
- `GET /api/v1/investment/dashboard` — 세 데이터 소스 병렬 조회 + Partial Success
- `InternalAccountAdapter` — 내부 원장 Mock (CB+Bulkhead+TL)
- `PartnerForeignStockAdapter` — 제휴사 해외 주식 Mock (CB+Bulkhead+TL)
- `RecommendationEngineAdapter` — 추천 엔진 Mock (CB+Bulkhead+TL)
- Resilience4j 차별화 설정 (데이터 소스별 CB/Bulkhead/TimeLimiter)
- Spring Boot 4.0 + Java 25 Virtual Thread 환경 구성
- Kotest + MockK + ArchUnit 테스트 스위트 (32개)
