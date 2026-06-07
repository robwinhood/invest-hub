# invest-hub

> 투자 서비스 진입 화면을 위한 통합 백엔드 API.  
> 세 가지 이질적인 데이터 소스(내부 원장, 제휴사 시스템, 추천 엔진)를 병렬 조합하여 단일 엔드포인트로 제공한다.

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

> **의존성 정책**: 모든 라이브러리는 **GA(정식 릴리즈)** 만 사용한다. RC·alpha·beta·milestone·SNAPSHOT 금지 (프로덕션 안전성). 자세한 근거: [의존성 버전 정책](#의존성-버전-정책-ga-전용).

---

## Quick Start

### Prerequisites

- Amazon Corretto 25 (또는 동일 버전의 OpenJDK 25+)

```bash
# Corretto 25 설치 예시 (macOS ARM64)
curl -L https://corretto.aws/downloads/latest/amazon-corretto-25-aarch64-macos-jdk.tar.gz \
  -o /tmp/corretto-25.tar.gz
mkdir -p ~/.jdks/corretto-25
tar -xzf /tmp/corretto-25.tar.gz -C ~/.jdks/corretto-25 --strip-components=1

export JAVA_HOME=~/.jdks/corretto-25/Contents/Home
```

### Run

```bash
JAVA_HOME=~/.jdks/corretto-25/Contents/Home ./gradlew bootRun
# 서버: http://localhost:8080
```

### Test

```bash
JAVA_HOME=~/.jdks/corretto-25/Contents/Home ./gradlew test
# 129개 테스트 전체 통과 확인
# 빌드 리포트: build/reports/tests/test/index.html
```

### PR 전 전체 검증 (파일 수정 없음)

```bash
JAVA_HOME=~/.jdks/corretto-25/Contents/Home ./gradlew check-all
# lintKotlin → test(Kotest + ArchUnit) 순서. 파일 수정 없음.
```

### 포맷 자동 수정 후 테스트

```bash
JAVA_HOME=~/.jdks/corretto-25/Contents/Home ./gradlew fix-all
# formatKotlin → test. 파일이 수정됨 — 커밋 전 사용.
```

---

## 핵심 설계 의사결정 (Risk → 대책 → 최적화 → 검증)

> 이 프로젝트의 가장 중요한 부분은 코드 그 자체가 아니라 **"문제를 어떻게 구체화하고, 어떤 리스크를 식별했으며, 그 리스크를 막기 위해 어떤 구조를 선택했는가"** 의 의사결정 과정이다.
> 아래 네 항목이 그 과정을 압축한 핵심 답변이며, 각 항목의 세부 근거는 [Design Decisions](#design-decisions) · [ADR 문서](docs/adr/)에 연결된다.

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
| **R1** | **Circuit Breaker → Bulkhead → TimeLimiter** 3단 데코레이션 + **어댑터별 전용 Virtual Thread Executor** + **Partial Success(Sealed Result)** | CB가 OPEN이면 하위 자원을 아예 소모하지 않도록 순서를 고정. 제휴사 지연이 `partner-vt-*` 풀에 갇혀 `account-vt-*`에 닿지 못하게 격리. 제휴사가 죽어도 해당 섹션만 `FAILURE`. | `ResilientAdapter`, `VirtualThreadConfig`, `InvestmentDashboard` · ADR-003/006 |
| **R2** | **Virtual Thread**(블로킹해도 OS 스레드 비점유) + **Semaphore Bulkhead**(동시성 상한, 대기 큐 0ms 즉시 거부) + **글로벌 RateLimiter**(입구 차단) | Thread-Pool Bulkhead는 가상 스레드 환경에서 무의미 → Semaphore로 동시 *호출 수*만 제어. 버스트는 큐에 쌓지 않고 즉시 429로 떨궈 GC 압박·메모리 폭증 방지. | `RateLimiterFilter`, `application.yml` |
| **R3** | **`ResilientAdapter`(실시간·캐시 없음)** vs **`CachingResilientAdapter`(저실시간·캐시 구조적 보장)** 의 **타입 수준 분리** | "해외 주식은 절대 캐시하면 안 된다"는 정책을 주석이 아니라 **상속하는 부모 클래스**로 강제. 캐시 추가/누락을 개발자 판단에 맡기지 않는다. | `ResilientAdapter`, `CachingResilientAdapter` · ADR-004 |
| **R4** | **Kotlin Sealed Class Result + `SectionStatus` enum** | 섹션별 성공/실패를 타입으로 표현해 "전체 실패" 자체가 코드상 불가능. 문자열 status 하드코딩 오타는 컴파일 타임 차단. | `InvestmentDashboard`, `InvestmentDashboardResponse` · ADR-006 |
| **R5** | **`WarmupService`** + **Startup/Readiness Probe 분리** + **`StartupReadyTracker` gap 메트릭** | 기동 시 대시보드를 사전 호출해 JIT·캐시·CB 윈도우를 데움. 웜업 완료 전 `/health/startup`이 503 → K8s가 준비된 Pod에만 트래픽 전달. | `WarmupService`, `HealthController`, `StartupReadyTracker` |
| **R6** | **`CacheInvalidationService` + `/admin/cache/**` + `evictAndRefresh`** | TTL을 기다리지 않고 즉시 무효화/재갱신. 재갱신은 비운 직후 능동 호출로 채워 사용자 캐시 미스 0. | `CacheInvalidationService`, `CacheAdminController` · ADR-007 |
| **R7** | **MDC(requestId·userId) 전 레이어 전파** + **어댑터별 스레드 명명** + **Actuator/Micrometer** | 가상 스레드는 ThreadLocal을 상속하지 않으므로 `supplyAsyncWithMdc()`로 명시 전파. 스레드 이름(`partner-vt-N`)만으로 장애 소스 즉시 식별. | `MdcFilter`, `InvestmentDashboardService`, `VirtualThreadConfig` |
| **R8** | **ArchUnit 6규칙**(레이어 경계·포트 인터페이스·순환 금지·**CB 누락 차단**·코루틴 금지) + **`CacheKeyVersionGenerator`** + **`configs.default` 안전망** | 외부 어댑터가 `ResilientAdapter`를 상속하지 않으면 **빌드 실패**. 캐시 대상 클래스 구조가 바뀌면 키 해시 자동 변경. 새 어댑터가 `portName`만 선언해도 기본 CB가 적용. | `HexagonalArchitectureTest`, `CacheKeyVersionGenerator` |

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

설계가 "그렇게 동작하길 기대한다"가 아니라 **테스트로 강제·회귀 방지**된다. 총 **129개 테스트 전체 통과**(`./gradlew check-all` → `BUILD SUCCESSFUL`).

| 최악 시나리오 | 검증 내용 | 검증 테스트 |
|---|---|---|
| **제휴사 타임아웃/장애** | 나머지 두 섹션은 정상 `SUCCESS`, 실패 섹션만 `FAILURE`로 격리 | `InvestmentDashboardServiceTest` (부분 실패) |
| **예외 → 사용자 표현 매핑** | `CallNotPermitted→CIRCUIT_OPEN`, `BulkheadFull→RESOURCE_EXHAUSTED`, `Timeout→TIMEOUT` 정확 분류 | `InvestmentDashboardServiceTest` (예외 분류) |
| **세 소스 동시성** | 세 소스가 각자 **다른 가상 스레드**에서 병렬 호출됨(threadId 수집 검증) | `VirtualThreadIsolationTest` (13) |
| **어댑터 장애 격리** | 어댑터별 전용 executor 분리·스레드 명명 검증 | `VirtualThreadIsolationTest` |
| **캐시 정확성** | 히트 시 원격 미호출, 미스 시 호출+적재, 키 독립성 | `CachingResilientAdapterTest` (4) |
| **스키마 변경 안전성** | 캐시 대상 클래스 필드 변경 시 키 해시 자동 변경(직렬화 충돌 방지) | `CacheKeyVersionGeneratorTest` (16) |
| **CB 누락 방지** | 외부 어댑터가 `ResilientAdapter` 미상속 시 **빌드 실패** | `HexagonalArchitectureTest` |
| **과부하 응답 코드** | RateLimit/Bulkhead→429, CB OPEN→503 (RFC 7807) | `GlobalExceptionHandlerTest`, `CacheAdminControllerTest` |
| **Cold start 허용성** | 웜업 일부 실패해도 서비스 기동·Liveness 유지 | `WarmupServiceTest`(7), `DashboardWarmerTest`(5), `HealthControllerTest`(7) |

```
$ ./gradlew check-all
> Task :lintKotlin        # Ktlint 포맷 통과
> Task :test              # Kotest + ArchUnit 129개 통과
BUILD SUCCESSFUL
```

전체 테스트 목록은 [Test Coverage](#test-coverage) 참고. 설계 결정의 상세 배경은 아래 [Design Decisions](#design-decisions)와 [ADR 문서](docs/adr/)에 정리되어 있다.

---

## API

전체 HTTP 엔드포인트 명세(집계 대시보드·도메인별 리소스·Cache Admin·Health Probe·Actuator)는 별도 문서로 분리했다.

- 📄 **명세 문서** → **[docs/api-reference.md](docs/api-reference.md)**
- 🧪 **인터랙티브 API 탐색기** (서버 없이 브라우저에서 Try it out) → **https://robwinhood.github.io/invest-hub/api/** (소스: [docs/api/](docs/api/index.html))

요약: 비즈니스 API는 `X-User-Id` 헤더가 필수이며, 집계 `GET /api/v1/investment/dashboard`는 부분 실패를 200+`status`로, 도메인별 엔드포인트(`/assets`·`/foreign-stocks`·`/recommendations`)는 데이터 속성별 `Cache-Control`과 HTTP 상태 코드로 표현한다. 설계 근거는 [ADR-008](docs/adr/008-aggregate-plus-resource-endpoints.md).

---

## Architecture

### Hexagonal Architecture (Ports & Adapters)

```
┌──────────────────────────────────────────────────────────┐
│                   Driving Adapters (In)                   │
│  web/      MdcFilter · RateLimiterFilter (필터)           │
│            InvestmentDashboardController (집계 REST)       │
│            Asset·ForeignStock·Recommendation Controller   │
│              (도메인별 리소스 REST + 속성별 Cache-Control) │
│            CacheAdminController (캐시 무효화·재갱신 REST)  │
│            HealthController (startup·ready·live Probe)    │
│  lifecycle/ WarmupInvoker (ApplicationReadyEvent 수신)    │
│             DashboardWarmer · StartupReadyTracker         │
├──────────────────────────────────────────────────────────┤
│                    Application Core                       │
│  Input Ports : GetInvestmentDashboardUseCase (집계)       │
│                GetAssetSummary·ForeignStockPortfolio·     │
│                RecommendedProducts UseCase (도메인별)      │
│  Domain      : InvestmentDashboard · Sealed Results      │
│                require() 불변식 — 생성 시점 입력값 차단   │
│  Service     : InvestmentDashboardService                │
│                WarmupService · CacheInvalidationService  │
│  Output Ports: AccountPort · ForeignStockPort            │
│                RecommendationPort · CacheEventPublisher  │
│                DistributedCacheStore (L2 추상화)         │
├──────────────────────────────────────────────────────────┤
│                   Driven Adapters (Out)                   │
│  ResilientAdapter<T>        — CB·Bulkhead·TL 템플릿       │
│  CachingResilientAdapter<T> — 캐시 구조적 보장 템플릿      │
│  ├── InternalAccountAdapter      (내부 원장 Mock)          │
│  ├── PartnerForeignStockAdapter  (제휴사 API Mock)         │
│  └── RecommendationEngineAdapter (추천 엔진 Mock + 캐시)   │
│  cache/ TwoTierCache (L1 Caffeine + L2 Mock Redis)        │
│         MockRedisStore (L2 + Pub/Sub) ·                   │
│         MockRedisCacheEventPublisher · L1EvictionSubscriber│
│         RecommendationCacheRefreshStrategy               │
└──────────────────────────────────────────────────────────┘
```

아키텍처 경계는 `HexagonalArchitectureTest`(ArchUnit)가 빌드 시 강제한다.  
경계를 위반하는 코드가 커밋되면 테스트가 즉시 실패한다.

**단일 모듈 선택 이유**: 단일 서비스·단일 기능 범위에서 패키지 구조로도 동일한 경계를 표현할 수 있으며, 멀티모듈의 빌드 복잡도를 감수할 이유가 없다.

---

## Design Decisions

### 1. 데이터 소스 특성별 차별화

세 데이터 소스는 소유 주체·실시간성·장애 영향도가 모두 다르다. 동일한 설정을 적용하면 비필수 데이터(추천 상품) 장애가 핵심 데이터(계좌) 조회에 영향을 주거나, 외부 의존 서비스(제휴사)가 내부 시스템을 연쇄 장애로 끌어들이는 문제가 생긴다.

| 항목 | 내부 계좌 | 해외 주식 (제휴사) | 추천 상품 |
|---|---|---|---|
| 소유 주체 | 내부 원장 | 외부 파트너 | 내부 ML팀 |
| 실시간성 | 높음 | 매우 높음 | 낮음 (수십 분 단위) |
| 장애 영향도 | 높음 (핵심 자산) | 중간 | 낮음 (참고 정보) |
| CB 실패율 임계 | 50% | **40%** (엄격) | **60%** (관대) |
| Bulkhead 한도 | 100 | **50** (엄격) | 80 |
| TimeLimiter | 2s | **3s** (외부 여유) | 1.5s |
| 캐시 | 없음 | 없음 (실시간 필수) | **L1 Caffeine + L2 Mock Redis 5분 TTL** |

### 2. Virtual Threads + 병렬 조회

Spring MVC + Virtual Threads를 채택한 이유:

- JDBC·레거시 라이브러리 등 블로킹 I/O를 그대로 사용 가능
- OS 스레드를 소비하지 않으면서 Reactive 수준의 동시성 확보
- 기존 명령형 코드 스타일 유지 → 팀 학습 비용 최소화

```yaml
spring.threads.virtual.enabled: true  # Tomcat 요청 처리를 Virtual Thread로
```

```kotlin
// 단일 요청 내 세 데이터 소스를 각각 독립 Virtual Thread에서 병렬 조회
// MDC는 ThreadLocal 기반이므로 Virtual Thread에 자동 전파되지 않는다.
// supplyAsyncWithMdc()가 호출 시점의 MDC를 캡처해 각 가상 스레드로 복원한다.
val assetFuture     = supplyAsyncWithMdc { fetchAssetSummary(userId) }
val stockFuture     = supplyAsyncWithMdc { fetchForeignStockPortfolio(userId) }
val recommendFuture = supplyAsyncWithMdc { fetchRecommendedProducts(userId) }

// 전체 응답 시간 ≈ max(계좌, 주식, 추천) — 순차 처리 대비 레이턴시 최소화
```

Java 25에서 Structured Concurrency (JEP 499)가 GA로 확정됐다. 현재 `CompletableFuture` 기반 병렬 조회는 향후 `StructuredTaskScope`로 자연스럽게 전환 가능하며, 취소 전파·오류 범위 제어 등 안전성이 더 향상된다.

### 3. Resilience4j 패턴 조합

```
CB → Bulkhead → TimeLimiter  (데코레이션 적용 순서)
```

- **Circuit Breaker**: 연속 실패가 누적되면 즉시 차단(OPEN)하여 장애 중인 시스템으로의 불필요한 요청과 스레드 점유를 동시에 차단한다.
- **Bulkhead (Semaphore)**: Virtual Thread 환경에서 Thread Pool Bulkhead는 의미가 없다. 가상 스레드는 블로킹해도 OS 스레드를 소비하지 않으므로 스레드 풀 격리의 이점이 사라진다. Semaphore Bulkhead로 동시 접근 수만 제어한다.
- **TimeLimiter**: 블로킹 호출에 시간 상한을 부여해 응답 레이턴시 SLA를 강제한다. 초과 시 `TimeoutException`을 던져 `FailureReason.TIMEOUT`으로 변환된다.

#### `ResilientAdapter<T>` — 내결함성 템플릿

CB → Bulkhead → TimeLimiter 3단 데코레이션이 세 어댑터에 동일하게 반복되던 것을 추상 기반 클래스로 제거했다. `portName` 선언 하나로 `application.yml`의 대응 설정이 자동으로 적용된다.

```kotlin
// 새 어댑터는 portName + 비즈니스 로직만 구현하면 CB·Bulkhead·TL이 자동 적용된다
@Component
class SomeNewAdapter(...) : ResilientAdapter<SomeData>(...), SomePort {
    override val portName = "some-new-source"       // application.yml 설정 키
    override fun getData(userId: String): SomeData = execute { callExternalApi(userId) }
}
```

### 4. 캐시 구조적 보장 — `CachingResilientAdapter<T>`

실시간성이 낮은 데이터 소스는 `CachingResilientAdapter`를 상속한다. **상속하는 순간 캐시가 구조적으로 보장**되므로, 개발자가 캐시 추가를 누락할 수 없다.

```
ResilientAdapter<T>         → 실시간 데이터 (해외 주식: 캐시 없음)
CachingResilientAdapter<T>  → 저실시간 데이터 (추천 상품: 캐시 필수)
```

이 구분 자체가 데이터 속성에 대한 명시적 설계 결정이다.

```kotlin
// 추천 엔진 어댑터는 CachingResilientAdapter를 상속
// → 별도 코드 없이 L1(Caffeine)+L2(Mock Redis) 이중 캐시가 자동 적용됨
@Component
class RecommendationEngineAdapter(
    ...,
    @Qualifier("recommendationsCache") cache: Cache,
) : CachingResilientAdapter<List<InvestmentProduct>>(..., cache), RecommendationPort {
    override val portName = "recommendation"
    override fun getRecommendedProducts(userId: String) = executeWithCache(userId)
    override fun callUncached(userId: String) = callRecommendationEngine(userId)
}
```

캐시 히트 시 CB·Bulkhead·TimeLimiter를 소모하지 않아 자원 효율도 높아진다.

### 5. Partial Success 패턴

API를 "전체 성공 또는 전체 실패"로 설계하면, 제휴사 장애 하나 때문에 내부 계좌 정보도 볼 수 없게 된다. 모바일 앱에서는 특히 비합리적이다.

Kotlin Sealed Class를 활용한 Result 타입으로 이 문제를 해결한다.

```kotlin
sealed class ForeignStockPortfolioResult {
    data class Success(val data: ForeignStockPortfolio) : ForeignStockPortfolioResult()
    data class Failure(val reason: FailureReason)      : ForeignStockPortfolioResult()
}
```

각 섹션의 `status`(`SectionStatus` enum)를 모바일 앱이 확인해 실패한 섹션에만 "일시적으로 조회할 수 없습니다" UI를 렌더링한다. `SectionStatus`를 enum으로 관리하여 "SUCCESS" 문자열 하드코딩에 의한 오타를 컴파일 타임에 차단한다.

### 6. 도메인 모델 불변식 검증

외부 API나 Mock이 음수 잔액, 0 수량 등 비정상 값을 반환했을 때 도메인 객체 생성 시점에 즉시 차단한다. 잘못된 데이터가 서비스 레이어나 응답 DTO까지 흘러내려가는 것을 방지한다.

```kotlin
data class SavingsAccount(...) {
    init {
        require(accountId.isNotBlank()) { "계좌 ID는 비어있을 수 없습니다" }
        require(balance >= BigDecimal.ZERO) { "잔액은 0 이상이어야 합니다: $balance" }
    }
}
```

### 7. MDC 기반 분산 요청 추적

`MdcFilter`가 요청 진입 시점에 `userId`와 `requestId`를 MDC에 등록한다.

**Virtual Thread MDC 전파 문제**: MDC는 `ThreadLocal` 기반이므로 `newVirtualThreadPerTaskExecutor()`로 생성된 가상 스레드는 parent의 MDC를 자동 상속하지 않는다. `supplyAsyncWithMdc()` 래퍼가 호출 시점의 MDC를 캡처해 각 가상 스레드에서 복원한다.

```kotlin
private fun <T> supplyAsyncWithMdc(supplier: () -> T): CompletableFuture<T> {
    val mdcContext = MDC.getCopyOfContextMap() ?: emptyMap()
    return CompletableFuture.supplyAsync({
        mdcContext.forEach(MDC::put)
        try { supplier() } finally { MDC.clear() }
    }, executor)
}
```

결과적으로 서비스·어댑터 레이어 모두 동일한 `requestId/userId`로 로그가 기록된다.

```
# 로그 출력 예시
20:26:10.826 [d9e52d05/user-001] [tomcat-handler-0] INFO  InvestmentDashboardService    - 대시보드 조회 시작
20:26:10.901 [d9e52d05/user-001] [virtual-59]       DEBUG InternalAccountAdapter         - 내부 원장 조회 완료
20:26:10.912 [d9e52d05/user-001] [virtual-60]       DEBUG RecommendationEngineAdapter    - 추천 엔진 조회 완료
20:26:11.135 [d9e52d05/user-001] [tomcat-handler-0] INFO  InvestmentDashboardService    - 대시보드 조회 완료
```

`X-Request-Id` 응답 헤더로 동일 값을 클라이언트에 전달한다.

### 8. 아키텍처 테스트 (ArchUnit)

`HexagonalArchitectureTest`가 빌드 시 다섯 가지 규칙을 강제한다.

| 규칙 | 의미 |
|---|---|
| 도메인 → 프레임워크 의존 금지 | 도메인이 Spring·Resilience4j를 import하면 빌드 실패 |
| 애플리케이션 → 어댑터 의존 금지 | 서비스가 어댑터 구현체를 직접 참조하면 빌드 실패 |
| 포트는 반드시 인터페이스 | 포트 패키지에 구현 클래스가 들어오면 빌드 실패 |
| 패키지 간 순환 의존 금지 | 순환이 생기면 빌드 실패 |
| Out 어댑터 CB 누락 방지 | `adapter.out.{internal,partner,recommendation}`의 `@Component`가 `ResilientAdapter`를 상속하지 않으면 빌드 실패 |

### 9. 캐시 키 자동 버전 관리 — `CacheKeyVersionGenerator`

**문제**: L2(Redis)는 **직렬화된 바이트**를 저장하므로, 직렬화 대상 클래스에 필드가 추가/삭제되면 구 포맷과 새 클래스가 충돌해 역직렬화 오류가 발생한다. 개발자가 매번 수동으로 캐시 키 버전을 올리면 휴먼 에러가 발생한다.

**해결**: 클래스 구조(프로퍼티 이름+타입)를 SHA-256으로 해시해 캐시 이름에 자동 삽입한다. 구조가 바뀌면 해시도 자동으로 바뀌어 새 키를 사용한다 — 사람 손이 필요 없다. 본 프로젝트는 L2(Mock Redis)가 실제로 직렬화 저장을 하므로 이 장치가 실효를 갖는다.

```kotlin
// CacheConfig.kt — InvestmentProduct 구조 기반 자동 버전
val cacheName = CacheKeyVersionGenerator.versionedName("recommendations", InvestmentProduct::class)
// → "recommendations:v7a0fe702"

// InvestmentProduct에 필드 추가 시 → "recommendations:v5e6f7a8b" (자동 변경)
```

기동 로그에서 확인 가능: `추천 상품 캐시 이름: recommendations:v7a0fe702 (InvestmentProduct 구조 기반 자동 버전)`

### 10. 이중 캐시 (L1 Caffeine + L2 Mock Redis) — `TwoTierCache`

추천 상품은 **L1(로컬 Caffeine) + L2(분산 Mock Redis)** 2계층으로 캐싱한다. `TwoTierCache`가 `org.springframework.cache.Cache`를 구현하므로 `CachingResilientAdapter`와 무효화 서비스는 **변경 없이** 동작한다.

```
조회: L1 hit → 반환
      L1 miss → L2 조회 → hit 시 L1 승격 후 반환 (원격 호출 0)
      L1·L2 miss → 추천 엔진 호출 → L1·L2 write-through
```

- **L1 (Caffeine)**: Pod-로컬, 가장 빠름. 대부분의 요청을 흡수.
- **L2 (Mock Redis)**: 전 Pod 공유, **직렬화 저장**(실제 Redis와 동일). 신규 Pod·L1 만료 시에도 원격 추천 엔진 호출을 줄여 자원 효율을 높인다.
- Mock Redis는 외부 의존성 없는 인메모리 구현(`MockRedisStore`)이다 — 과제 제약("외부 시스템은 모두 Mock") + GA 전용 정책(embedded-redis 등 비-GA 회피)에 부합. 실 환경에선 Lettuce 기반 `RedisDistributedCacheStore`로 교체하면 되고, `DistributedCacheStore` 포트를 구현하므로 상위 코드는 불변.

### 11. 캐시 무효화·재갱신 — L1·L2 동시 무효화 + 분산 전파

ML 모델 교체, 운영 데이터 긴급 수정 시 TTL 만료를 기다리지 않고 즉시 캐시를 비우거나 다시 채운다. **무효화는 L1·L2를 모두 비우고**, Pub/Sub으로 다른 Pod의 L1까지 전파한다.

```
CacheInvalidationService (오케스트레이터)
  ├─ TwoTierCache.evict ──→ L1(Caffeine) + L2(Mock Redis) 동시 제거
  ├─ CacheEventPublisher (port) ──→ MockRedisCacheEventPublisher (활성: Mock Redis Pub/Sub 발행)
  │                                  └→ NoOpCacheEventPublisher (Redis 미연동 시 fallback)
  └─ CacheRefreshStrategy (port) ─→ RecommendationCacheRefreshStrategy (즉시 재갱신)

분산 전파:  무효화 Pod ─┐  evict 이벤트 발행
                       ├─→ Mock Redis Pub/Sub ──→ L1EvictionSubscriber ──→ 타 Pod L1 evict
  (L2는 공유 저장소라 1회 evict로 전 Pod 반영, L1만 Pub/Sub으로 전파)
```

| 동작 | 메서드 | 효과 |
|---|---|---|
| 무효화 | `evict(name, key)` | L1·L2 즉시 제거 + 타 Pod L1 전파 |
| 무효화+재갱신 | `evictAndRefresh(name, key)` | 무효화 직후 능동 재조회 → 사용자 캐시 미스 없음 |
| 전체 무효화 | `evictAll(name)` | L1·L2 전체 제거 |

`CacheAdminController`(`/admin/cache/**`)가 이를 REST로 노출한다. (API 섹션 참고)
`MockRedisCacheEventPublisher`(빈 이름 `redisCacheEventPublisher`)가 존재하므로 `NoOpCacheEventPublisher`는 `@ConditionalOnMissingBean`으로 자동 비활성화된다. 실 Redis 전환 시 이 발행자만 교체하면 코어 코드는 불변.

### 12. Spring MVC vs WebFlux

| | Spring MVC + Virtual Threads | WebFlux (Reactive) |
|---|---|---|
| 코드 스타일 | 블로킹, 직관적 | Non-blocking, 함수형 |
| JDBC 호환 | 자연스러움 | R2DBC 필요 |
| 팀 학습 비용 | 낮음 | 높음 |
| 처리량 | Virtual Thread로 충분 | 극한 처리량 |

금융 서비스에서 DB 연동은 필수이고 JDBC는 블로킹이다. Virtual Thread로 블로킹 코드를 그대로 유지하면서 높은 동시성을 얻는 것이 현실적이다.

### 13. API 입자도 — 집계 + 도메인별 리소스 (왜 단일 엔드포인트만으론 부족한가)

"단일 응답 스펙"을 **엔드포인트 1개로만** 제공하면 설계 제약(나)과 충돌한다:
- 한 응답에 실시간(주식)·저실시간(추천)이 섞여 **캐시 정책이 가장 휘발성 높은 데이터 기준으로 강제**됨 → 추천에 `Cache-Control` 불가 (제약 3 위배).
- "주식만 새로고침"이 자산·추천까지 재조회 → 자원 낭비 (제약 2 위배).
- 세 도메인의 갱신·실패·캐싱 생명주기가 HTTP 경계에서 강제 결합 (제약 1 위배).

그래서 **하이브리드**를 택했다: 집계(`/dashboard`)는 첫 화면 1회 렌더용으로 유지하고, 도메인별 단독 엔드포인트(`/assets`·`/foreign-stocks`·`/recommendations`)로 **독립 갱신 + 데이터 속성별 `Cache-Control`**을 제공한다. 내부에서만 하던 "데이터 속성별 처리 최적화"를 HTTP 경계까지 확장한 것이다. 과도 분할(집계 없이 도메인만)은 첫 화면 N회 호출로 모바일에 손해라 기각했다. 상세 근거·대안 비교: [ADR-008](docs/adr/008-aggregate-plus-resource-endpoints.md).

---

## Test Coverage

```
총 129개 테스트 — 전체 통과

HexagonalArchitectureTest          (6)  아키텍처 경계 + CB 누락 방지 + 코루틴 금지 (ArchUnit)
InvestmentDashboardServiceTest    (16)  서비스 정상·부분 실패·예외 분류·병렬 실행·도메인 검증
InvestmentDashboardControllerTest  (7)  집계 HTTP 계층, 헤더 검증, totalAsset 계산
InvestmentResourceControllerTest   (7)  도메인별 엔드포인트·속성별 Cache-Control·실패 상태 매핑(503/504/429)
GlobalExceptionHandlerTest         (1)  에러 응답 형식 (RFC 7807)
CachingResilientAdapterTest        (4)  캐시 히트·미스·독립 키·결과 일관성
TwoTierCacheTest                   (8)  L1+L2 저장/조회·직렬화 라운드트립·L1·L2 동시 무효화·Pub/Sub
CacheKeyVersionGeneratorTest      (16)  클래스 구조 해시·필드 변경 감지·순환참조·캐싱
CacheInvalidationServiceTest       (9)  무효화·재갱신·이벤트 발행·전략 선택·예외 격리
CacheAdminControllerTest           (5)  캐시 목록·무효화·재갱신 REST 엔드포인트
VirtualThreadIsolationTest        (13)  어댑터별 Executor 격리·스레드 명명·Virtual Thread·병렬성
WarmupServiceTest                  (7)  웜업 완료 상태·다중 Warmer·실패 허용성
DashboardWarmerTest                (5)  대시보드 사전 호출 횟수·예외 허용·Partial Success
StartupReadyTrackerTest            (5)  probe gap 메트릭 NaN 초기값·첫 번째 probe 기록
HealthControllerTest               (7)  Startup·Readiness·Liveness Probe 엔드포인트
InvestHubApplicationTests          (1)  Spring 컨텍스트 로드
```

---

## Project Structure

```
src/main/kotlin/com/investhub/
├── InvestHubApplication.kt
├── config/
│   ├── CacheConfig.kt                              # Caffeine 캐시 빈 (추천 5분 TTL)
│   ├── CacheKeyVersionGenerator.kt                 # 클래스 구조 해시 → 캐시 키 자동 버전
│   └── VirtualThreadConfig.kt                      # 어댑터별 독립 Virtual Thread Executor 빈
├── domain/
│   ├── account/AssetSummary.kt                     # 계좌·자산 도메인 모델 (require 검증)
│   ├── stock/ForeignStockPortfolio.kt              # 해외 주식 도메인 모델 (require 검증)
│   ├── product/InvestmentProduct.kt                # 추천 상품 도메인 모델 (require 검증)
│   └── dashboard/InvestmentDashboard.kt            # Sealed Result 타입 + 집계 모델
├── application/
│   ├── port/input/
│   │   ├── GetInvestmentDashboardUseCase.kt        # 집계 유스케이스
│   │   └── Get{AssetSummary,ForeignStockPortfolio,RecommendedProducts}UseCase.kt  # 도메인별
│   ├── port/output/
│   │   ├── {Account,ForeignStock,Recommendation}Port.kt
│   │   ├── CacheEventPublisher.kt                  # 캐시 무효화 이벤트 발행 포트
│   │   └── DistributedCacheStore.kt               # L2(분산 Mock Redis) 포트
│   └── service/
│       ├── InvestmentDashboardService.kt           # 병렬 조회 + MDC 전파 + Partial Success
│       ├── CacheInvalidationService.kt             # 캐시 무효화·재갱신 오케스트레이터
│       ├── CacheRefreshStrategy.kt                 # 캐시 재갱신 전략 포트
│       ├── Warmer.kt                               # 웜업 작업 추상화 인터페이스
│       └── WarmupService.kt                        # 웜업 오케스트레이션 서비스
└── adapter/
    ├── in/
    │   ├── lifecycle/                              # Spring 이벤트 기반 인바운드 어댑터
    │   │   ├── WarmupInvoker.kt                    # ApplicationReadyEvent → 웜업 트리거
    │   │   ├── DashboardWarmer.kt                  # Warmer 구현체 · 유스케이스 사전 호출
    │   │   └── StartupReadyTracker.kt              # 웜업→K8s Probe gap 메트릭 (NaN sentinel)
    │   └── web/                                    # HTTP 기반 인바운드 어댑터
    │       ├── filter/
    │       │   ├── MdcFilter.kt                    # userId/requestId MDC 등록 · X-Request-Id
    │       │   └── RateLimiterFilter.kt            # 인스턴스당 15K TPS 상한 · 즉시 429
    │       ├── dto/InvestmentDashboardResponse.kt  # Sealed → JSON (SectionStatus enum)
    │       ├── SectionHttpStatus.kt                # FailureReason → HTTP 상태(503/504/429) 매핑
    │       ├── GlobalExceptionHandler.kt           # RFC 7807 · 429 · 503 일관된 에러 응답
    │       ├── HealthController.kt                 # /health/startup · /ready · /live Probe
    │       ├── CacheAdminController.kt             # /admin/cache/** 무효화·재갱신 API
    │       ├── AssetController.kt                  # GET /assets (private,max-age=30)
    │       ├── ForeignStockController.kt           # GET /foreign-stocks (no-store, 실시간)
    │       ├── RecommendationController.kt         # GET /recommendations (private,max-age=300)
    │       └── InvestmentDashboardController.kt
    └── out/
        ├── ResilientAdapter.kt                     # CB+Bulkhead+TL 템플릿 (실시간 어댑터용)
        ├── CachingResilientAdapter.kt              # 캐시 구조적 보장 템플릿 (저실시간 어댑터용)
        ├── internal/InternalAccountAdapter.kt      # 내부 원장 Mock (accountExecutor)
        ├── partner/PartnerForeignStockAdapter.kt   # 제휴사 API Mock (partnerExecutor)
        ├── recommendation/RecommendationEngineAdapter.kt  # 추천 엔진 Mock + 캐시
        └── cache/
            ├── TwoTierCache.kt                     # L1(Caffeine)+L2(Mock Redis) 2계층 Cache 구현
            ├── MockRedisStore.kt                   # 인메모리 Mock Redis (L2 KV+TTL+Pub/Sub)
            ├── MockRedisCacheEventPublisher.kt     # 분산 무효화 발행 (Mock Redis Pub/Sub, 활성)
            ├── L1EvictionSubscriber.kt             # Pub/Sub 수신 → 타 Pod L1 무효화
            ├── NoOpCacheEventPublisher.kt          # Redis 미연동 시 fallback (@ConditionalOnMissingBean)
            └── RecommendationCacheRefreshStrategy.kt  # 추천 캐시 재갱신 전략
   (application/port/output/DistributedCacheStore.kt — L2 분산 캐시 포트)

src/test/kotlin/com/investhub/  (총 129개 테스트)
├── architecture/HexagonalArchitectureTest.kt       # ArchUnit: 경계·CB 누락·코루틴 금지
├── config/
│   ├── CacheKeyVersionGeneratorTest.kt             # 구조 해시·필드 변경 감지
│   └── VirtualThreadIsolationTest.kt               # Executor 격리·스레드 명명·병렬성
├── adapter/in/lifecycle/                           # WarmupService · DashboardWarmer · Tracker
├── adapter/in/web/                                 # Dashboard·Health·CacheAdmin·예외 핸들러
├── adapter/out/CachingResilientAdapterTest.kt      # 캐시 히트/미스 검증
├── adapter/out/cache/TwoTierCacheTest.kt           # L1+L2 이중 캐시·직렬화·이중 무효화·Pub/Sub
├── application/service/                            # Dashboard·CacheInvalidation 서비스
└── InvestHubApplicationTests.kt
```

---

## 의존성 버전 정책 (GA 전용)

**이 프로젝트는 GA(General Availability, 정식 릴리즈) 버전만 사용한다.**
RC·alpha·beta·milestone·SNAPSHOT 등 검증이 끝나지 않은 버전은 사람·AI 누구든 도입 금지다.
대고객 금융 서비스의 안전성 원칙이다.

**근거가 된 실제 사례:**
- Spring Boot 4.1.0-RC1을 한때 사용했으나 RC(릴리즈 후보)이므로 GA인 **4.0.6**으로 교체했다.
- Detekt는 Kotlin 2.3.21 지원 버전이 alpha(2.0.0-alpha.x)뿐이라 **채택하지 않았다.** 대신 정적 분석 역할을 GA 도구인 Ktlint(포맷·스타일)와 ArchUnit(아키텍처·코루틴 금지·CB 누락)으로 대체했다. Detekt 2.x가 GA로 출시되면 재검토한다.

**재발 방지:**
- 새 의존성 추가 시 `/self-review` 체크리스트의 "안전성 — 버전·의존성" 항목이 GA 여부를 점검한다.
- `CLAUDE.md`의 절대 금지 사항에 명시되어 있다.

---

## AI 개발 워크플로우

이 프로젝트는 **PRD → 설계 → 개발+테스트 → 비판적 검토 → 사람 리뷰** 5단계를 따른다.
**1~4단계는 AI가 자동 수행**하고, **5단계(리뷰·머지)만 사람이 담당**한다.

```
① PRD          ② 설계        ③ 개발+테스트     ④ 비판적 검토      ⑤ 사람 리뷰
(양식 작성)  →  (AI/ADR)   →  (/add-datasource) → (/self-review) →  (PR + CI)
═══════════════════ AI 자동 구간 ═══════════════════════════════   ══ 사람 ══
                    └────────────── /ship (PRD→PR, 적응형) ──────────────┘
```

`/ship`이 이 **AI 자동 구간 전체(①요구사항 분석 ~ ⑤PR 생성)** 를 한 번에 끌고 간다(적응형 — 이미 끝난 단계는 건너뜀). 사람은 PRD 제공과 **리뷰·승인·머지**(⑤의 본질)에만 리소스를 쓴다.
전체 흐름과 각 단계의 자동화 장치는 [`docs/ai-dev-workflow.md`](docs/ai-dev-workflow.md)에 정리되어 있다.
