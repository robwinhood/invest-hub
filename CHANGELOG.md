# CHANGELOG

모든 주요 변경사항을 이 파일에 기록한다.  
형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.0.0/)를 따른다.

---

## [Unreleased]

### Added

### Changed

### Fixed

---

## [0.4.0] — 2026-06-07

### Added
- **자동 릴리즈에 GitHub Release 발행 추가** — `release.yml`이 `vX.Y.Z` 태그를 만든 직후, CHANGELOG의 `[X.Y.Z]` 섹션을 릴리즈 노트로 하여 GitHub Release를 자동 생성한다(`gh release create … --latest`). 기존엔 태그까지만 자동이고 Release(발표 페이지)는 수동이었다.

### Changed

### Fixed

---

## [0.3.0] — 2026-06-07

### Added
- **추천 캐시 인프라 — 2계층 캐시(L1 Caffeine + L2 Mock Redis) + 자동 키 버전 + 분산 무효화·재갱신 + Admin API**.
  - **2계층 캐시** `TwoTierCache`(`org.springframework.cache.Cache` 구현): L1 hit→반환 / L1 miss·L2 hit→L1 승격 / 둘 다 miss→원본 호출 후 L1·L2 write-through. `CachingResilientAdapter`·무효화 서비스는 변경 없이 동작.
  - **L2 추상화** `DistributedCacheStore` 포트 + `MockRedisStore`(인메모리 Mock Redis: KV + TTL + Pub/Sub, `keysByPrefix`=Redis `SCAN` 대응). 외부 의존성 없이 Redis 모사(GA 정책상 embedded-redis 회피), 실 환경은 Lettuce 구현으로 포트만 교체. L2는 Jackson 3로 **직렬화 저장**.
  - **자동 키 버전** `CacheKeyVersionGenerator`: 캐시 대상 클래스 구조(필드명+타입) SHA-256 해시를 캐시 이름에 삽입(`recommendations:v7a0fe702`). `InvestmentProduct` 필드 변경 시 키 자동 교체 → L2 직렬화 충돌 원천 차단(수동 버전업 불필요).
  - **무효화·재갱신** `CacheInvalidationService`(+`CacheEventPublisher`/`CacheRefreshStrategy`): `evict`/`evictAll`/`evictAndRefresh`(재갱신은 비운 직후 능동 호출 → 캐시 미스 0). 무효화는 **L1·L2 모두** 제거 후 `MockRedisCacheEventPublisher`(빈 `redisCacheEventPublisher`) → `L1EvictionSubscriber` Pub/Sub으로 타 Pod L1 전파(`NoOpCacheEventPublisher`는 `@ConditionalOnMissingBean`으로 자동 비활성화). `RecommendationCacheRefreshStrategy` 구현.
  - **Cache Admin API** `CacheAdminController`: `GET /admin/cache`(엔트리 키까지 노출 — `{ caches:[{ name, baseName, entryCount, keys }], count }`로 이름/키 혼동 해소), `DELETE /admin/cache/{name}/{key}`, `DELETE /admin/cache/{name}`, `POST .../{key}/refresh`, `POST .../refresh`. 키 열거는 `CacheKeyEnumerable` 포트 + `TwoTierCache.keys()`(L1·L2 합집합, L2 prefix 환원).
  - **로컬 시딩** `LocalCacheSeeder`: `./gradlew bootRun`(`local` 프로파일) 기동 직후 데모 사용자(`user-001`~`user-003`) 추천 캐시를 사전 적재 → 바로 `GET /admin/cache`에서 evict 가능한 키 확인·시험 가능. (Swagger 탐색기는 백엔드 없는 브라우저 목이라 무관하게 정적 Mock 유지.)
  - 의존성: `tools.jackson.module:jackson-module-kotlin` 추가(Spring Boot 4 관리 jackson-bom 3.1.2, GA). 테스트: `TwoTierCacheTest`·`CacheInvalidationServiceTest`·`CacheAdminControllerTest`·`CacheKeyVersionGeneratorTest`·`LocalCacheSeederTest`.
- **API 명세 문서 분리 + 서버리스 인터랙티브 탐색기**.
  - `docs/api/api-reference.md` 신규 — 전체 HTTP 엔드포인트(집계 대시보드·도메인별 리소스·Cache Admin·Health Probe·Actuator) 단일 명세. README의 `## API` 섹션 본문은 제거하고 이 문서 링크만 남김(중복 제거).
  - `docs/api/` 신규 — **백엔드 없이 동작하는 인터랙티브 API 탐색기**. Swagger UI(CDN) + `openapi.yaml` + 브라우저 내 목(`window.fetch` 인터셉트). "Try it out" 시 실제 서버 대신 invest-hub의 결정적 Mock 응답을 반환(외부 시스템이 전부 Mock이라 가능). GitHub Pages 게시 완료 — **https://robwinhood.github.io/invest-hub/api/** 에서 접근(소스: `main`/`docs`).
  - Actuator 호스트 표기 정정: 관리 포트 **8081**(기존 README 예시의 8080은 `management.server.port`와 불일치).
- **하이브리드 API — 집계 엔드포인트 + 도메인별 리소스 엔드포인트** (ADR-008).
  - 기존 `GET /dashboard`(집계, 첫 화면)는 유지하고 도메인 단독 엔드포인트 3종 추가: `GET /assets`, `GET /foreign-stocks`, `GET /recommendations`.
  - **데이터 속성별 `Cache-Control`**: 자산 `private,max-age=30` / 주식 `no-store`(실시간) / 추천 `private,max-age=300`(L2 TTL 정렬) / 집계 `no-store`. → "데이터 속성별 최적화"를 내부 캐시뿐 아니라 HTTP 경계까지 확장.
  - 도메인 단독 엔드포인트는 부분 실패를 **상태 코드**(503/504/429)로, 집계는 200+섹션 `status`로 표현(`SectionHttpStatus.kt`).
  - 입력 유스케이스 3종(`GetAssetSummaryUseCase`·`GetForeignStockPortfolioUseCase`·`GetRecommendedProductsUseCase`) 추가, `InvestmentDashboardService`가 4개 유스케이스를 구현하며 **집계가 도메인 유스케이스를 병렬 재사용**(중복 제거).
  - `ADR-008` 추가 — "단일 집계 API만으론 설계 제약(독립성·자원·속성별 최적화)과 충돌하는 이유"와 하이브리드 근거 명문화.
  - 테스트 `InvestmentResourceControllerTest`(7) 추가.
- **AI 개발 워크플로우 5단계 도입** (PRD → 설계 → 개발+테스트 → 비판적 검토 → 사람 리뷰).
  - `docs/ai/ai-dev-workflow.md` — 전체 프로세스·단계별 자동화 장치 명문화
  - `.claude/commands/self-review.md` — 비판적 자가 검토 skill (④단계)
  - `/add-datasource` skill에 ④ 자가 검토 단계 통합
  - PR 템플릿에 "AI 자동 단계 완료 + 자가 검토 보고서" 섹션 추가
- **`/ship` skill** (`.claude/commands/ship.md`) — **PRD에서 PR까지 전체 개발 파이프라인 자동화**. PRD를 주면 `①요구사항분석 → ②설계(필요 시 ADR) → ③개발+테스트(/add-datasource 활용) → ④자가검토(/self-review) → check-all → commit → push → ⑤PR 생성`을 한 번에 오케스트레이션한다. **적응형**(이미 끝난 단계는 건너뜀 — 코드가 다 됐으면 ④부터 "마무리 모드"). 리뷰·승인·머지(⑤ 본질)는 사람 몫이며 "머지까지" 지시 시 CI 통과 확인 후 자동 머지. 보호 브랜치엔 직접 커밋하지 않고 피처 브랜치를 자동 생성, `gh` 미인증·`check-all` 실패 시 중단하는 가드레일 포함.
  - `/ship` 사용법 안내를 `CLAUDE.md`(워크플로우 + 전용 섹션), `docs/ai/ai-dev-workflow.md`(⑤단계 + 자동화 장치 표), `docs/ai/ai-dev-guide.md`(시나리오 5)에 반영. "④~⑤만 자동화"라는 초기 부정확 표현을 전체 파이프라인 정의로 교정.
  - 스킬 구성 정리: `/self-review`·`/add-datasource`는 `/ship`이 호출하는 빌딩블록이자 단독 사용 가능 도구로 역할 명문화. `ship.md` ④에 add-datasource 경유 시 self-review 중복 실행 방지 명시.
  - `docs/template/prd-datasource-template.md`를 "권장(자연어로 줘도 ①요구사항분석이 보완)" 톤으로 보강.
- **고가용성(100K TPS) 대응**.
  - `application.yml` — HTTP/2, GZIP 압축, Tomcat 연결 10K, 관리 포트 8081 분리
  - Rate Limiter — 인스턴스당 15K TPS 상한, 초과 시 즉시 429 반환 (`RateLimiterFilter`)
  - Resilience4j `configs.default` — 새 어댑터가 portName만 선언해도 CB 자동 적용 (휴먼 에러 방지)
  - CB 슬라이딩 윈도우 10→100, Bulkhead 50→4,000 (100K TPS 수치 재산정)
- **웜업 & Startup Probe** (신규 Pod cold-start 및 K8s readiness gap 대응).
  - `WarmupService` / `Warmer` 인터페이스 / `DashboardWarmer` — 기동 시 JIT+캐시 사전 적재
  - `StartupReadyTracker` — 웜업→K8s Probe gap 메트릭 (NaN sentinel, Datadog 오염 방지)
  - `WarmupInvoker` — ApplicationReadyEvent 수신 시 자동 실행
  - `HealthController` — `/health/startup`, `/health/ready`, `/health/live` 전용 엔드포인트
  - 웜업 테스트 (`WarmupServiceTest`, `DashboardWarmerTest`, `StartupReadyTrackerTest`, `HealthControllerTest`)
- **어댑터별 독립 Virtual Thread Executor 분리** (장애 격리 + 스레드 명명).
  - `accountExecutor` / `partnerExecutor` / `recommendationExecutor` / `serviceExecutor`
  - 스레드 이름 규칙: `{역할}-vt-N` (로그/APM에서 역할 즉시 식별 가능)
  - Micrometer ExecutorServiceMetrics 등록으로 `executor.*` 메트릭 노출
  - `VirtualThreadIsolationTest` — executor 격리·이름·Virtual Thread·병렬성 검증 (13개)
- ArchUnit `outAdaptersMustExtendResilientAdapter` 규칙 — CB 누락 어댑터 빌드 차단
- `GlobalExceptionHandler` — RFC 7807 Problem Details 형식의 일관된 에러 응답(429 RateLimiter·429 Bulkhead·503 CB OPEN 포함), `GlobalExceptionHandlerTest` 동반
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
  - `.github/workflows/ci.yml` — GitHub Actions CI: lint(Ktlint) → test 2-job (push/PR 시 자동)
  - `.github/pull_request_template.md` — PR 리뷰 체크리스트
- `docs/ai/ai-dev-guide.md` — AI 자동 개발·운영 활용 가이드
- `docs/template/prd-datasource-template.md` — skill 사용법 포함 상세 PRD 가이드
- `CHANGELOG.md` — 변경 이력 관리

### Changed
- **README에 「과제 3대 요구사항 → 충족 방식」 매핑 표 추가** — 검토자가 과제 요구사항을 곧바로 코드/테스트로 확인할 수 있게.
  - 기존 README는 구현자 관점의 리스크 코드(R1~R8)·결정 구조 (1)~(4)로 정리돼 있어, 과제의 **3대 요구사항(① 서비스 독립성 ② 효율적 리소스 통제 ③ 데이터 속성별 처리 최적화)** 과의 대응이 암묵적이었다. 최상단에 요구사항별 `한 줄 답 · 핵심 메커니즘 · 코드로 증명(테스트) · 상세(R코드·ADR)` 매핑 표를 추가해 30초 안에 충족 여부를 확인하고 세부로 드릴다운할 수 있게 했다. 코드 변경 없음(문서 전용).
- **문서 재구성 — 검토자 가독성 중심**. 거짓 정보 없이 실제 프로젝트 구성만 반영.
  - `README.md`를 **과제 필수 답변 4항목**(① 잠재적 위험 분석 ② 아키텍처 의사결정·대책 ③ 성능·자원 최적화 ④ 신뢰성 검증 결과)에 집중하도록 재작성. README에는 4항목과 링크만 남김(중복 제거).
  - `docs/project-summary.md` → **`docs/project-qna.md`** 로 이름 변경(검토자 온보딩 + 설계 리뷰 Q&A 역할 반영). README "📖 문서 안내" 섹션에서 링크.
  - **단일 출처화(중복 제거)**: 깊이 있는 내용은 새 파일을 만드는 대신 각 내용의 단일 출처로 환원. 설계 결정 → `docs/adr/`, 헥사고날 다이어그램·패키지 구조 → `docs/project-qna.md`(코드 구조 §5), 테스트 목록 → `docs/project-qna.md`(테스트 현황), 의존성 GA 정책 → `CLAUDE.md`. README는 이 출처들로 직접 링크한다. (재구성 과정에서 한때 만들었던 `docs/architecture.md`는 ADR·project-qna·CLAUDE와 내용이 중복되어 제거 — 테스트 수 등 사실이 여러 파일로 흩어져 드리프트하던 문제 해소.)
- **관리 포트 분리(8080/8081) 근거 문서화** — 동작 변경 없음(주석·문서만).
  - `application.yml` — `server`/`management` 블록에 분리 이유 3가지(보안 격리·K8s 프로브 격리·리소스 격리)를 주석으로 명문화. 설정을 바꾸는 사람이 가장 먼저 보는 지점에 근거가 없던 누락 보완.
  - `docs/api/api-reference.md` §5 — "왜 8080이 아니라 8081인가" 설명 추가. `README.md` — 관리 포트 분리 셀에 보안·프로브 격리 근거 보강.
- **README에 핵심 설계 의사결정 섹션 추가**: (1) 잠재적 위험 분석(R1–R8) (2) 리스크별 아키텍처 의사결정·대책 (3) 성능·자원 최적화 (4) 신뢰성 검증 결과(시나리오↔테스트 매핑)를 README 상단에 정리.
- 코드 주석·문서의 내부 프로젝트/티켓 참조를 일반화 (`StartupReadyTracker`, `CacheKeyVersionGenerator`, ADR-007).
- `docs/project-qna.md`의 서술 톤을 프로젝트 리뷰 문서에 맞게 정리.
- `/ship`(`ship.md`)의 PR 생성 시 `--assignee "@me"`를 기본 적용 — PR Assignee를 현재 로그인 계정으로 자동 지정.
- **`/add-datasource` 스킬을 하이브리드 API 패턴으로 갱신**(PR #6 반영, STEP 8→10): ① 입력 유스케이스(`Get{X}UseCase`, Sealed Result 반환) 생성 단계 + ② 도메인 단독 리소스 컨트롤러(`{X}Controller`, 속성별 `Cache-Control`, `SectionHttpStatus` 재사용) 생성 단계 추가, ③ 서비스 단계를 'private fetch'에서 '공개 유스케이스 구현 + 집계가 병렬 재사용'으로 교정, ④ 리소스 컨트롤러 테스트 추가. 참고 파일 목록에 `AssetController`·`GetAssetSummaryUseCase`·`SectionHttpStatus` 추가.
- 워크플로우 문서의 "**8개 파일** 자동 생성" 표기를 하이브리드 반영 후 실제 수치(**약 10개**: 도메인·포트·어댑터·설정·Result·서비스·응답 DTO·입력 유스케이스·도메인 리소스 컨트롤러·테스트)로 정정 (`docs/ai/ai-dev-workflow.md`·`docs/ai/ai-dev-guide.md`·`docs/project-qna.md`·`CLAUDE.md`).
- **미사용 Jackson 2 `jackson-module-kotlin` 의존성 제거** — Spring Boot 4.0은 Jackson 3(`tools.jackson`)을 쓰고 코드에 Jackson 2 `ObjectMapper`가 없어, `com.fasterxml.jackson.module:jackson-module-kotlin`(Jackson 2 Kotlin 모듈)은 사용되지 않던 leftover였다. 제거 후 `check-all`(133개) 전체 통과 확인. (`@JsonInclude` 등 annotation은 `jackson-annotations`에서 전이 제공되므로 영향 없음.)
- **의존성 정책: GA(정식 릴리즈) 전용으로 확정.** RC·alpha·beta·SNAPSHOT 도입 금지를 `CLAUDE.md` 절대 금지 사항과 README에 명시.
- Detekt **미채택 결정**: GA(1.23.8)는 Kotlin 2.3.21 비호환, Kotlin 2.3.21 지원 버전은 alpha뿐. 정적 분석 역할을 Ktlint(포맷)+ArchUnit(아키텍처·코루틴 금지)으로 분담. (관련 `detekt.yml`, `detekt-test.yml` 삭제)
- ADR-007 추가 — 캐시 키 자동 버전 + 무효화·재갱신 설계
- `application.yml` — Graceful Shutdown 설정 추가 (`server.shutdown: graceful`)
- `CLAUDE.md` — CI/PR 프로세스, 에러 처리 규칙 추가

### Fixed
- **Cache Admin `refresh`가 evict만 하고 재갱신을 누락하던 문제 수정**.
  - 원인 ①(이름 비대칭): `GET /admin/cache`가 주는 versioned 이름(`recommendations:v7a0fe702`)을 `refresh` 경로에 붙여넣으면, evict는 `it == name || startsWith("$name:")`로 매칭돼 동작했지만 재갱신 전략 탐색은 `supports("recommendations")`(기본 이름)만 알아 **조용히 누락**됐다. → `CacheInvalidationService`가 전략 탐색·호출 시 항상 기본 이름으로 정규화(`baseNameOf`).
  - 원인 ②(키 없는 전체 재갱신 no-op): 추천은 userId 단위 캐시라 `POST /admin/cache/{name}/refresh`(키 없음)는 무엇을 다시 불러올지 알 수 없어 재갱신이 사실상 동작하지 않았다. → `evictAllAndRefresh`가 **비우기 직전** `CacheKeyEnumerable.keys()`로 캐시돼 있던 userId들을 수집해, evict 후 키별로 재갱신("현재 캐시된 사용자 전체 재데움"). 빈 캐시는 전략의 `key=null` 재갱신에 위임(no-op), 일부 키 실패 시 나머지는 계속 진행.
  - 회귀 테스트 4건 추가(`CacheInvalidationServiceTest`): versioned 이름 per-key refresh, versioned 전체 재갱신의 키별 호출, 빈 캐시의 `key=null` 위임, 일부 키 실패 시 격리.
- **Cache Admin `DELETE` 응답 정확도**: 특정 키 무효화 시 실제 제거 여부를 반영해 `status`를 `evicted`(실제 지움) 또는 `not_found`(원래 부재)로 구분. 이전에는 키가 없어도 무조건 `evicted`로 응답해, evict가 동작한 것처럼 보이던 오인을 유발. `Cache.evictIfPresent`/`DistributedCacheStore.evictIfPresent` 기반으로 L1·L2 양쪽의 실제 존재 여부를 판정.
- **문서 전반의 테스트 수 표기를 실제 테스트 리포트와 동기화** — 현재 총 **133개**. README·HELP·`project-qna.md`의 클래스별 개수를 실측에 맞춤(`TwoTierCacheTest` 13, `CacheInvalidationServiceTest` 17, `CacheAdminControllerTest` 6, `CacheKeyVersionGeneratorTest` 16, `LocalCacheSeederTest` 2 등). `HexagonalArchitectureTest`는 테스트가 아니라 ArchUnit 규칙 6개. `project-qna.md`의 ADR 개수 8개로 정정. (이전 "101개"·"110개"·"117개"·"129개" 등 사이클 중간 집계 표기 정리.)
- PR 템플릿의 "AI 자동 단계 완료 확인(워크플로우 ①~④)" 체크리스트에 누락돼 있던 **②(설계) 항목 추가**. 헤더는 "①~④"로 명시하면서 정작 ②만 빠져 있어 `docs/ai/ai-dev-workflow.md`의 5단계 정의와 불일치하던 문제 정정.
- PR 템플릿에서 "①~④" 헤더와 어긋나던 **5번째 체크(`check-all`)를 ④의 하위 검증 항목으로 들여쓰기** — 번호 없는 5번째 peer처럼 보이던 불일치 정정(check-all은 별도 단계가 아니라 ④의 검증 게이트).
- **CI 파이프라인 복구**: `.github/workflows/ci.yml`이 존재하지 않는 `detektMain`/`detektTest` 태스크를 호출해 lint 잡이 항상 실패하던 문제 수정 (Detekt 미채택 결정과 불일치). `lintKotlin` → `test`(Kotest + ArchUnit) 구조로 정정.
- `docs/project-qna.md` Q3의 "Detekt가 코루틴을 차단한다" 오기재를 **ArchUnit `noCoroutineUsage`** 로 정정 (Detekt는 미채택).
- `HELP.md`의 Spring Boot 4.1 참조 링크를 **4.0** 으로 정정 (실제 채택 버전 일치).

---

## [0.2.0] — 2026-06-06

### Added
- `CLAUDE.md` — AI 세션 간 컨텍스트 유지를 위한 가이드 문서
- `docs/adr/` — 설계 결정 기록 6개 (ADR-001 ~ ADR-006)
- `.claude/commands/add-datasource.md` — 새 데이터 소스 자동 생성 skill
- `docs/template/prd-datasource-template.md` — PRD 작성 가이드 및 템플릿
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

## [0.1.0] — 2026-06-06

### Added
- 투자 대시보드 API 최초 구현
- `GET /api/v1/investment/dashboard` — 세 데이터 소스 병렬 조회 + Partial Success
- `InternalAccountAdapter` — 내부 원장 Mock (CB+Bulkhead+TL)
- `PartnerForeignStockAdapter` — 제휴사 해외 주식 Mock (CB+Bulkhead+TL)
- `RecommendationEngineAdapter` — 추천 엔진 Mock (CB+Bulkhead+TL)
- Resilience4j 차별화 설정 (데이터 소스별 CB/Bulkhead/TimeLimiter)
- Spring Boot 4.0 + Java 25 Virtual Thread 환경 구성
- Kotest + MockK + ArchUnit 테스트 스위트 (32개)
