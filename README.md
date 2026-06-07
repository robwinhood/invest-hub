# invest-hub

> 투자 서비스 진입 화면을 위한 통합 백엔드 API.
> 세 가지 이질적인 데이터 소스(내부 원장, 제휴사 시스템, 추천 엔진)를 병렬 조합하여 단일 엔드포인트로 제공한다.

이 README는 **"무엇을 만들었는가"가 아니라 "어떤 리스크를 식별하고, 그것을 막기 위해 어떤 구조를 선택했으며, 그 설계가 실제로 방어해 냄을 어떻게 증명했는가"** 라는 의사결정 과정에 초점을 맞춘다. 코드 워크스루·기술 선택 배경·설계 리뷰 Q&A는 별도 문서로 분리했다 → [📖 문서 안내](#-문서-안내).

---

## 과제 3대 요구사항 → 충족 방식 (한눈에 보기)

> 과제가 요구한 **세 가지 설계 항목**을, 이 프로젝트가 **어디서·무엇으로 충족했고 어떻게 코드로 증명했는지** 한 표로 매핑한다.
> 세부 근거는 바로 아래 「핵심 설계 의사결정 (1)~(4)」(리스크 코드 R1~R8)과 [ADR 문서](docs/adr/)로 이어진다.

| 과제 요구사항 | 한 줄 답 | 핵심 메커니즘 | 코드로 증명 | 상세 |
|---|---|---|---|---|
| **① 서비스 독립성 확보**<br>특정 데이터 영역의 장애·지연이 전체 및 정상 도메인에 전파되지 않을 것 | 장애를 **섹션 단위로 격리** — 한 소스가 죽어도 나머지 섹션은 정상 응답한다 | 어댑터별 **전용 Virtual Thread Executor**(장애 풀 격리) + **CB→Bulkhead→TimeLimiter** 3단 차단 + **Partial Success(Sealed Result)** | `InvestmentDashboardServiceTest`(부분 실패), `VirtualThreadIsolationTest`(executor 격리) | (2) R1·R4 · [ADR-003](docs/adr/003-resilient-adapter-pattern.md)·[ADR-006](docs/adr/006-partial-success-sealed-class.md) |
| **② 효율적인 리소스 통제**<br>불특정 다수의 대규모 요청에서 시스템 자원 효율을 극대화할 것 | 블로킹해도 **OS 스레드를 점유하지 않고**, 입구·동시성 **이중 상한**으로 폭주를 차단한다 | **Virtual Thread** + **Semaphore Bulkhead**(Little's Law로 산정) + **글로벌 RateLimiter**(15K TPS·`timeout 0ms` 즉시 429) | `GlobalExceptionHandlerTest`·`CacheAdminControllerTest`(429/503), `VirtualThreadIsolationTest`(가상 스레드) | (3) R2 · [ADR-001](docs/adr/001-spring-mvc-over-webflux.md) |
| **③ 데이터 속성별 처리 최적화**<br>도메인별 비즈니스 가치·실시간성에 맞는 가공·제어 방식 | 실시간/저실시간을 **타입으로 분리**해 캐시 정책을 *구조로 강제*한다(누락 불가) | **`ResilientAdapter`**(실시간·캐시 배제) vs **`CachingResilientAdapter`**(저실시간·L1+L2 캐시 자동) + **속성별 `Cache-Control`** | `CachingResilientAdapterTest`(히트/미스), `TwoTierCacheTest`(L1+L2), `InvestmentResourceControllerTest`(속성별 Cache-Control) | (3) R3 · [ADR-004](docs/adr/004-caching-resilient-adapter.md)·[ADR-008](docs/adr/008-aggregate-plus-resource-endpoints.md) |

> 세 항목 모두 **"런타임 점검"이 아니라 "컴파일·빌드 시점의 구조적 강제"** 로 보장한다는 것이 이 설계의 일관된 원칙이다(R8 — ArchUnit이 CB 누락·레이어 위반을 빌드에서 차단). 전체 검증 결과는 아래 [(4) 신뢰성 검증 결과](#4-신뢰성-검증-결과--최악-시나리오를-코드로-증명) 참고.

---

## 핵심 설계 의사결정 — Risk → 대책 → 최적화 → 검증

> 이 프로젝트의 가장 중요한 부분은 코드 그 자체가 아니라 **"문제를 어떻게 구체화하고, 어떤 리스크를 식별했으며, 그 리스크를 막기 위해 어떤 구조를 선택했는가"** 의 의사결정 과정이다.
> 아래 네 항목이 그 과정을 압축한 핵심 답변이며, 각 항목의 세부 근거는 결정별 [ADR 문서](docs/adr/) · 구조/Q&A는 [docs/project-qna.md](docs/project-qna.md)에 연결된다.

### (1) 잠재적 위험 분석 — 상용 금융 플랫폼 관점

세 데이터 소스는 **소유 주체·네트워크 신뢰도·데이터 생명주기**가 모두 다르다. 이를 상용 대고객 금융 서비스에 그대로 적용한다고 가정하면 다음 리스크가 예상된다.

| # | 리스크 | 금융 서비스에서의 구체적 위험 |
|---|---|---|
| **R1** | **연쇄 장애 (Cascading Failure)** | 외부 제휴사 시스템의 지연/장애가 요청 스레드를 점유한 채 누적되면, 아무 문제 없는 **내부 계좌 조회까지 응답 불가**로 번진다. "투자 화면이 통째로 안 뜨는" 전면 장애. |
| **R2** | **자원 고갈 (Resource Exhaustion)** | 불특정 다수의 대규모 요청 유입 시, 느린 외부 호출이 동시 요청 수만큼 스레드·커넥션·힙을 묶는다 → 스레드 폭증 → GC 폭주 → 전체 정지. |
| **R3** | **데이터 신선도/일관성 충돌** | 실시간성이 다른 데이터를 동일 정책으로 다루면, **실시간 잔고에 stale 캐시를 물려 잘못된 금액을 노출**(금융 사고)하거나, 저실시간 데이터를 매 요청 원격 호출(불필요 부하)하게 된다. |
| **R4** | **All-or-Nothing 응답** | 비핵심 데이터(추천) 하나의 장애로 핵심 데이터(내 자산)까지 못 보여주는 설계는 대고객 신뢰를 직접 훼손한다. |
| **R5** | **Cold Start / 배포 중 품질 저하** | 신규 Pod가 JIT 미최적화·빈 캐시·빈 CB 윈도우 상태에서 트래픽을 받으면 첫 요청 레이턴시가 급등하고, CB가 적은 샘플에 과민 반응한다. |
| **R6** | **운영 중 긴급 정정 불가** | ML 모델 교체·데이터 오류 발생 시 TTL 만료(수 분)를 기다려야만 정상화된다. |
| **R7** | **관측성 부재** | 장애 시 *어느* 소스가 원인인지, *어떤* 사용자 요청이 문제였는지 분산 환경에서 추적 불가. |
| **R8** | **점진적 구조 붕괴 (휴먼/AI 에러)** | 새 데이터 소스 추가 시 CB 누락, 캐시 누락, 레이어 경계 위반이 누적되어 아키텍처가 서서히 무너진다. |

### (2) 아키텍처 의사결정 및 대책 — 리스크별 선제 방어

각 리스크를 **구조(타입·아키텍처) 수준에서 차단**하는 것을 원칙으로 했다. 런타임 점검이 아니라 컴파일·빌드 시점에 막아야 휴먼/AI 에러가 끼어들 여지가 없기 때문이다.

| 리스크 | 도입한 대책 | 왜 이렇게 설계했나 | 위치 |
|---|---|---|---|
| **R1** | **Circuit Breaker → Bulkhead → TimeLimiter** 3단 데코레이션 + **어댑터별 전용 Virtual Thread Executor** + **Partial Success(Sealed Result)** | CB가 OPEN이면 하위 자원을 아예 소모하지 않도록 순서를 고정. 제휴사 지연이 `partner-vt-*` 풀에 갇혀 `account-vt-*`에 닿지 못하게 격리. 제휴사가 죽어도 해당 섹션만 `FAILURE`. | `ResilientAdapter`, `VirtualThreadConfig`, `InvestmentDashboard` · [ADR-003](docs/adr/003-resilient-adapter-pattern.md)/[006](docs/adr/006-partial-success-sealed-class.md) |
| **R2** | **Virtual Thread**(블로킹해도 OS 스레드 비점유) + **Semaphore Bulkhead**(동시성 상한, 대기 큐 0ms 즉시 거부) + **글로벌 RateLimiter**(입구 차단) | Thread-Pool Bulkhead는 가상 스레드 환경에서 무의미 → Semaphore로 동시 *호출 수*만 제어. 버스트는 큐에 쌓지 않고 즉시 429로 떨궈 GC 압박·메모리 폭증 방지. | `RateLimiterFilter`, `application.yml` |
| **R3** | **`ResilientAdapter`(실시간·캐시 없음)** vs **`CachingResilientAdapter`(저실시간·캐시 구조적 보장)** 의 **타입 수준 분리** | "해외 주식은 절대 캐시하면 안 된다"는 정책을 주석이 아니라 **상속하는 부모 클래스**로 강제. 캐시 추가/누락을 개발자 판단에 맡기지 않는다. | `ResilientAdapter`, `CachingResilientAdapter` · [ADR-004](docs/adr/004-caching-resilient-adapter.md) |
| **R4** | **Kotlin Sealed Class Result + `SectionStatus` enum** | 섹션별 성공/실패를 타입으로 표현해 "전체 실패" 자체가 코드상 불가능. 문자열 status 하드코딩 오타는 컴파일 타임 차단. | `InvestmentDashboard`, `InvestmentDashboardResponse` · [ADR-006](docs/adr/006-partial-success-sealed-class.md) |
| **R5** | **`WarmupService`** + **Startup/Readiness Probe 분리** + **`StartupReadyTracker` gap 메트릭** | 기동 시 대시보드를 사전 호출해 JIT·캐시·CB 윈도우를 데움. 웜업 완료 전 `/health/startup`이 503 → K8s가 준비된 Pod에만 트래픽 전달. | `WarmupService`, `HealthController`, `StartupReadyTracker` |
| **R6** | **`CacheInvalidationService` + `/admin/cache/**` + `evictAndRefresh`** | TTL을 기다리지 않고 즉시 무효화/재갱신. 재갱신은 비운 직후 능동 호출로 채워 사용자 캐시 미스 0. | `CacheInvalidationService`, `CacheAdminController` · [ADR-007](docs/adr/007-cache-key-versioning-and-invalidation.md) |
| **R7** | **MDC(requestId·userId) 전 레이어 전파** + **어댑터별 스레드 명명** + **Actuator/Micrometer** | 가상 스레드는 ThreadLocal을 상속하지 않으므로 `supplyAsyncWithMdc()`로 명시 전파. 스레드 이름(`partner-vt-N`)만으로 장애 소스 즉시 식별. | `MdcFilter`, `InvestmentDashboardService`, `VirtualThreadConfig` |
| **R8** | **ArchUnit 규칙**(레이어 경계·포트 인터페이스·순환 금지·**CB 누락 차단**·코루틴 금지) + **`CacheKeyVersionGenerator`** + **`configs.default` 안전망** | 외부 어댑터가 `ResilientAdapter`를 상속하지 않으면 **빌드 실패**. 캐시 대상 클래스 구조가 바뀌면 키 해시 자동 변경. 새 어댑터가 `portName`만 선언해도 기본 CB가 적용. | `HexagonalArchitectureTest`, `CacheKeyVersionGenerator` |

> 각 대책의 결정 배경 → [ADR 문서](docs/adr/) · 구조 다이어그램·패키지 위치 → [docs/project-qna.md (코드 구조 §5)](docs/project-qna.md#5-코드-구조--헥사고날-아키텍처)

### (3) 성능 및 자원 최적화 — 트래픽 증가·한계 상황 대비

| 설계 요소 | 효과 | 근거 수치 |
|---|---|---|
| **병렬 조회** (3소스 동시 호출) | 응답 시간 = `max(계좌, 주식, 추천)` (순차 합산이 아님) | 순차 ~680ms → 병렬 ~400ms |
| **Virtual Thread** | I/O 대기 중 캐리어 OS 스레드 반납 → 수만 동시 요청을 적은 OS 스레드로 처리 | `spring.threads.virtual.enabled: true` |
| **Bulkhead 한도 (Little's Law)** | 동시성 상한을 *실측 기반*으로 산정해 과소·과대 차단 모두 방지 | 제휴사 15K TPS × 0.24s ≈ 3,600 → **4,000** / 계좌 1,000 / 추천 2,000 |
| **글로벌 RateLimiter** | 버스트를 입구에서 즉시 429로 차단 → 큐 적재·GC 압박 차단 | 인스턴스당 **15K TPS**, `timeout 0ms` |
| **캐시 히트 시 CB·Bulkhead·TL 미소모** | 저실시간 데이터의 자원 점유 최소화 | 추천 L1+L2 5분 TTL |
| **L2(Mock Redis) 공유 캐시** | 신규 Pod·L1 만료 시에도 추천 엔진 원격 호출을 흡수 → 원본 부하·꼬리 레이턴시 감소 | L1 miss → L2 hit 시 원격 호출 0 |
| **TimeLimiter 꼬리 레이턴시 상한** | 느린 호출이 SLA를 넘기지 못하도록 강제 | 계좌 2s / 제휴사 3s / 추천 1.5s |
| **HTTP/2 · GZIP · Tomcat 튜닝 · 관리 포트 분리** | 커넥션 효율·페이로드 축소. 관리 포트 분리는 **보안 격리**(내부 상태 노출 actuator를 공개 포트에서 제외)·**프로브 격리**·장애 중 8081 접근을 모두 노린다 | `max-connections 10K`, 관리 포트 **8081** |
| **Graceful Shutdown** | 배포·스케일인 시 진행 중 요청 보존 | `server.shutdown: graceful`, 30s |

### (4) 신뢰성 검증 결과 — 최악 시나리오를 코드로 증명

설계가 "그렇게 동작하길 기대한다"가 아니라 **테스트로 강제·회귀 방지**된다. 총 **133개 테스트 전체 통과**(`./gradlew check-all` → `BUILD SUCCESSFUL`).

| 최악 시나리오 | 검증 내용 | 검증 테스트 |
|---|---|---|
| **제휴사 타임아웃/장애** | 나머지 두 섹션은 정상 `SUCCESS`, 실패 섹션만 `FAILURE`로 격리 | `InvestmentDashboardServiceTest` (부분 실패) |
| **예외 → 사용자 표현 매핑** | `CallNotPermitted→CIRCUIT_OPEN`, `BulkheadFull→RESOURCE_EXHAUSTED`, `Timeout→TIMEOUT` 정확 분류 | `InvestmentDashboardServiceTest` (예외 분류) |
| **세 소스 동시성** | 세 소스가 각자 **다른 가상 스레드**에서 병렬 호출됨(threadId 수집 검증) | `VirtualThreadIsolationTest` |
| **어댑터 장애 격리** | 어댑터별 전용 executor 분리·스레드 명명 검증 | `VirtualThreadIsolationTest` |
| **캐시 정확성** | 히트 시 원격 미호출, 미스 시 호출+적재, 키 독립성 | `CachingResilientAdapterTest`, `TwoTierCacheTest` |
| **스키마 변경 안전성** | 캐시 대상 클래스 필드 변경 시 키 해시 자동 변경(직렬화 충돌 방지) | `CacheKeyVersionGeneratorTest` |
| **CB 누락 방지** | 외부 어댑터가 `ResilientAdapter` 미상속 시 **빌드 실패** | `HexagonalArchitectureTest` |
| **과부하 응답 코드** | RateLimit/Bulkhead→429, CB OPEN→503 (RFC 7807) | `GlobalExceptionHandlerTest`, `CacheAdminControllerTest` |
| **Cold start 허용성** | 웜업 일부 실패해도 서비스 기동·Liveness 유지 | `WarmupServiceTest`, `DashboardWarmerTest`, `HealthControllerTest` |

```
$ ./gradlew check-all
> Task :lintKotlin        # Ktlint 포맷 통과
> Task :test              # Kotest + ArchUnit 133개 통과
BUILD SUCCESSFUL
```

> 클래스별 테스트 개수 전체 목록 → [docs/project-qna.md (테스트 현황)](docs/project-qna.md#테스트-현황)

---

## Tech Stack

| Category | Detail |
|---|---|
| Language | Kotlin **2.3.21** (GA) |
| Framework | Spring Boot **4.0.6** (GA, Spring Framework 7 / Spring MVC) |
| Java | Java 25 JDK · JVM target 21 (Virtual Threads — JEP 491) |
| Architecture | Hexagonal Architecture (단일 모듈, Ports & Adapters) |
| Resilience | Resilience4j **2.4.0** (Circuit Breaker · Semaphore Bulkhead · TimeLimiter · RateLimiter) |
| Cache | **L1 Caffeine + L2 Mock Redis 이중 캐시** (추천 5분 TTL) · 자동 키 버전(`CacheKeyVersionGenerator`) · 분산 무효화(Mock Redis Pub/Sub) · 무효화·재갱신 API |
| Observability | MDC (requestId · userId 전 레이어 전파) · Spring Boot Actuator (포트 8081 분리) |
| Code Quality | Ktlint 1.5 (`kotlinter`) · ArchUnit 규칙 · `.editorconfig` |
| Test | Kotest **6.1.11** · MockK **1.14.11** · ArchUnit **1.4.2** (아키텍처 경계·코루틴 금지·CB 누락 강제) |
| CI | GitHub Actions (Lint → Test → Build) |
| Build | Gradle 8 (Kotlin DSL) |

> **의존성 정책**: 모든 라이브러리는 **GA(정식 릴리즈)** 만 사용한다. RC·alpha·beta·milestone·SNAPSHOT 금지 (프로덕션 안전성). 근거·강제 방식: [CLAUDE.md (절대 금지 사항)](CLAUDE.md) · 실제 사례 Q&A: [docs/project-qna.md Q15·Q16](docs/project-qna.md).

---

## Quick Start

> JDK 설치를 포함한 상세 환경 설정·빌드 명령은 [HELP.md](HELP.md) 참고.

```bash
export JAVA_HOME=~/.jdks/corretto-25/Contents/Home   # Amazon Corretto 25 (또는 OpenJDK 25+)

./gradlew bootRun     # 로컬 서버 — API: http://localhost:8080, 관리: 8081
./gradlew test        # 133개 테스트 (리포트: build/reports/tests/test/index.html)
./gradlew check-all   # PR 전 전체 검증: lintKotlin → test (파일 수정 없음)
./gradlew fix-all     # formatKotlin → test (포맷 자동 수정 — 커밋 전 사용)
```

---

## API

전체 HTTP 엔드포인트 명세(집계 대시보드·도메인별 리소스·Cache Admin·Health Probe·Actuator)는 별도 문서로 분리했다.

- 📄 **명세 문서** → **[docs/api/api-reference.md](docs/api/api-reference.md)**
- 🧪 **인터랙티브 API 탐색기** (서버 없이 브라우저에서 Try it out) → **https://robwinhood.github.io/invest-hub/api/** (소스: [docs/api/](docs/api/index.html))

요약: 비즈니스 API는 `X-User-Id` 헤더가 필수이며, 집계 `GET /api/v1/investment/dashboard`는 부분 실패를 200+`status`로, 도메인별 엔드포인트(`/assets`·`/foreign-stocks`·`/recommendations`)는 데이터 속성별 `Cache-Control`과 HTTP 상태 코드로 표현한다. 설계 근거는 [ADR-008](docs/adr/008-aggregate-plus-resource-endpoints.md).

---

## 📖 문서 안내

검토 목적에 따라 아래 문서를 함께 보면 좋다.

| 보고 싶은 것 | 문서 |
|---|---|
| **프로젝트를 처음 접하고, 설계 의사결정을 Q&A로 빠르게 파악** | **[docs/project-qna.md](docs/project-qna.md)** — 문서 안내 · 구현 워크스루 · 아키텍처 다이어그램·패키지 구조 · 기술 선택 이유 · 테스트 현황 · 설계 리뷰 Q&A 16문항 |
| 결정별 배경(WebFlux 대신 MVC, 코루틴 미사용 등) | [docs/adr/](docs/adr/) (ADR-001 ~ 008) |
| HTTP API 명세 | [docs/api/api-reference.md](docs/api/api-reference.md) |
| 환경 설정 · 새 데이터 소스 추가 실무 가이드 | [HELP.md](HELP.md) |
| AI 개발 워크플로우(PRD→PR 5단계) | [docs/ai/ai-dev-workflow.md](docs/ai/ai-dev-workflow.md) |
| 변경 이력 | [CHANGELOG.md](CHANGELOG.md) |

> 이 프로젝트는 **PRD → 설계 → 개발+테스트 → 비판적 검토 → 사람 리뷰** 5단계로 개발됐다. 1~4단계는 AI(Claude Code)가 자동 수행하고, 5단계(리뷰·머지)만 사람이 담당한다. 전체 흐름은 [docs/ai/ai-dev-workflow.md](docs/ai/ai-dev-workflow.md)에 정리되어 있다.

<!-- mobile /ship roundtrip test (2026-06-07) — 검토 후 닫아도 무방 -->
