# 프로젝트 구현 정리 — invest-hub

> 리뷰어가 이 프로젝트를 빠르게 파악하기 위한 문서다.
> "무엇을, 왜, 어떻게 만들었는가"를 사람이 읽기 위해 쉽게 정리했다.

---

## 한 줄 요약

**"투자 서비스 진입 화면의 세 가지 데이터를 한 번에 가져오되, 하나가 망가져도 나머지는 정상 동작하는 API"**

---

## 함께 보면 좋은 문서

| 문서 | 무엇이 들어 있나 |
|---|---|
| [README.md](../README.md) | 프로젝트 소개 + **과제 필수 답변**(리스크 → 대책 → 최적화 → 검증) |
| [docs/adr/](adr/) | 설계 결정 8건의 배경 (왜 코루틴 미사용, 왜 캐시가 추천에만 등) |
| [docs/api/api-reference.md](api/api-reference.md) | HTTP API 전체 명세 |
| [HELP.md](../HELP.md) | 환경 설정 · 새 데이터 소스 추가 실무 가이드 |
| [docs/ai/ai-dev-workflow.md](ai/ai-dev-workflow.md) | AI 개발 5단계 워크플로우 |
| [CHANGELOG.md](../CHANGELOG.md) | 변경 이력 |
| [CLAUDE.md](../CLAUDE.md) | AI(Claude Code)용 규칙·제약 (코드 컨벤션·금지 사항) |

---

## 만들어야 했던 것

사용자가 투자 앱에 들어오면 한 화면에 세 가지 정보가 보여야 한다:

1. **내 계좌/자산** — 예금, 펀드, 채권 잔액 (내부 원장 데이터)
2. **해외 주식** — 애플, 마이크로소프트 등 보유 현황 (제휴사 외부 시스템)
3. **추천 투자 상품** — 나에게 맞는 ETF, 펀드 추천 (ML 추천 엔진)

세 곳에서 데이터를 가져와 하나의 API 응답으로 만드는 것이 목표다.
세 소스는 **소유 주체·네트워크 신뢰도·데이터 생명주기가 모두 다르다** — 이 차이를 어떻게 설계에 녹였는지가 이 프로젝트의 핵심이다.

---

## 핵심 구현 포인트 5가지

### 1. 세 개를 동시에 가져온다 (병렬 조회)

순서대로 가져오면 총 시간 = A + B + C. 동시에 가져오면 총 시간 = max(A, B, C).

```
순차: [계좌 80ms] → [주식 400ms] → [추천 200ms] = 680ms
병렬: [계좌][주식][추천] 동시 시작                = 400ms
```

Java 25의 **Virtual Thread**(가상 스레드)로 세 요청을 동시에 보낸다.
가상 스레드는 수천 개를 만들어도 메모리를 거의 쓰지 않는다.

### 2. 하나가 망가져도 나머지는 보여준다 (Partial Success)

```json
{
  "assetSummary": { "status": "SUCCESS", "totalBalance": 23751867 },
  "foreignStockPortfolio": { "status": "FAILURE", "failureReason": "TIMEOUT" },
  "recommendedProducts":   { "status": "SUCCESS", "productList": [...] }
}
```

모바일 앱은 `status`를 보고 실패한 섹션에만 "잠시 후 다시 시도해주세요"를 보여준다.

### 3. 외부 시스템 장애가 내부까지 번지지 않게 막는다 (Resilience4j)

```
[Circuit Breaker] → [Bulkhead] → [TimeLimiter]
     차단기              격벽            타이머
```

- **차단기**: 연속 실패 시 해당 시스템으로 연결을 끊는다. 일정 시간 후 다시 시도.
- **격벽**: 동시에 접근할 수 있는 요청 수를 제한한다.
- **타이머**: 정해진 시간 안에 응답 없으면 포기한다.

외부 제휴사는 실패율 임계를 가장 엄격하게(40% 차단), 추천 엔진은 관대하게(60%) 둔다. 동시 처리 한도(Bulkhead)는 트래픽 규모에 맞춰 Little's Law로 산정한다 — 계좌 1000·제휴사 4000·추천 2000.

### 4. 캐시 — 추천 상품은 매번 새로 가져오지 않아도 된다

```
해외 주식 → 실시간 → 캐시 없음              (ResilientAdapter 상속)
추천 상품 → 저실시간 → L1+L2 이중 캐시 5분   (CachingResilientAdapter 상속)
            L1 = 로컬 Caffeine, L2 = 공유 Mock Redis (직렬화 저장)
```

캐시 적용이 **코드 구조로 강제**된다. `CachingResilientAdapter`를 상속하면 캐시가 자동으로 붙는다. 깜빡할 수 없다.

**추가로 캐시 운영에서 사람 손이 가던 부분 3가지를 자동화했다:**

| 문제 | 자동화 |
|---|---|
| 데이터 클래스 변경 시 캐시 키 충돌 | `CacheKeyVersionGenerator` — 클래스 구조 해시를 키에 자동 삽입 (`recommendations:v7a0fe702`). 필드 추가 시 키 자동 변경, 사람이 버전 올릴 필요 없음 |
| TTL 만료 전 긴급 캐시 비우기 | `/admin/cache/**` API — 무효화·전체 무효화 |
| 캐시 비운 뒤 첫 요청의 지연 | 무효화 + 즉시 재갱신 API — 사용자가 캐시 미스를 경험하지 않음 |

### 5. 코드 구조 — 헥사고날 아키텍처

```
[바깥] HTTP 컨트롤러, 외부 API 어댑터
  ↓ 의존
[중간] 유스케이스 서비스, 포트 인터페이스
  ↓ 의존
[안쪽] 순수 도메인 모델 (Spring 의존 없음)
```

이 규칙을 사람이 지키는 게 아니라 **테스트가 자동으로 강제**한다. 위반하면 빌드 실패. (경계 강제: `HexagonalArchitectureTest`(ArchUnit), 단일 모듈 근거: [ADR-005](adr/005-single-module.md))

#### 전체 구조 다이어그램 (Ports & Adapters)

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

#### 패키지 구조

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
│   ├── port/input/                                 # 컨트롤러가 호출하는 유스케이스 (집계 + 도메인별)
│   ├── port/output/                                # 어댑터가 구현하는 포트 (Port·CacheEventPublisher·DistributedCacheStore)
│   └── service/
│       ├── InvestmentDashboardService.kt           # 병렬 조회 + MDC 전파 + Partial Success
│       ├── CacheInvalidationService.kt             # 캐시 무효화·재갱신 오케스트레이터
│       ├── CacheRefreshStrategy.kt                 # 캐시 재갱신 전략 포트
│       └── Warmer.kt · WarmupService.kt            # 웜업 추상화 + 오케스트레이션
└── adapter/
    ├── in/
    │   ├── lifecycle/                              # Spring 이벤트 진입점
    │   │   ├── WarmupInvoker.kt                    # ApplicationReadyEvent → 웜업 트리거
    │   │   ├── DashboardWarmer.kt                  # Warmer 구현체 · 유스케이스 사전 호출
    │   │   ├── StartupReadyTracker.kt              # 웜업→K8s Probe gap 메트릭 (NaN sentinel)
    │   │   └── LocalCacheSeeder.kt                 # local 프로파일 추천 캐시 데모 시딩
    │   └── web/                                    # HTTP 진입점 (필터·컨트롤러·DTO)
    │       ├── filter/                             # MdcFilter · RateLimiterFilter
    │       ├── dto/InvestmentDashboardResponse.kt  # Sealed → JSON (SectionStatus enum)
    │       ├── SectionHttpStatus.kt                # FailureReason → HTTP 상태(503/504/429) 매핑
    │       ├── GlobalExceptionHandler.kt           # RFC 7807 · 429 · 503 일관된 에러 응답
    │       ├── HealthController.kt                 # /health/startup · /ready · /live Probe
    │       ├── CacheAdminController.kt             # /admin/cache/** 무효화·재갱신 API
    │       ├── AssetController · ForeignStockController · RecommendationController  # 도메인별 + 속성별 Cache-Control
    │       └── InvestmentDashboardController.kt    # 집계
    └── out/
        ├── ResilientAdapter.kt                     # CB+Bulkhead+TL 템플릿 (실시간 어댑터용)
        ├── CachingResilientAdapter.kt              # 캐시 구조적 보장 템플릿 (저실시간 어댑터용)
        ├── internal/InternalAccountAdapter.kt      # 내부 원장 Mock (accountExecutor)
        ├── partner/PartnerForeignStockAdapter.kt   # 제휴사 API Mock (partnerExecutor)
        ├── recommendation/RecommendationEngineAdapter.kt  # 추천 엔진 Mock + 캐시
        └── cache/                                  # TwoTierCache(L1+L2) · MockRedisStore · Pub/Sub 발행·구독 · 재갱신 전략
```

> 각 설계 결정의 *이유*는 [ADR 문서](adr/)에, 코드 컨벤션·금지 규칙은 [CLAUDE.md](../CLAUDE.md)에 있다.

---

## 기술 선택 이유 (간단 요약)

| 기술 | 선택 이유 |
|---|---|
| **Kotlin 2.3.21** | Java보다 간결하고 Null 안전 (GA) |
| **Spring Boot 4.0.6** | 최신 GA 버전, Spring Framework 7 기반 (RC·alpha 금지 — GA 전용 정책) |
| **Java 25 Virtual Thread** | 코루틴 없이 높은 동시성, 블로킹 코드 그대로 사용 |
| **Resilience4j 2.4** | Spring 친화적인 내결함성 (CB·Bulkhead·TimeLimiter·RateLimiter) |
| **헥사고날 아키텍처** | 외부 시스템 교체 시 핵심 로직 변경 최소화 |
| **Kotlin Sealed Class** | Partial Success를 타입 안전하게 표현 |
| **이중 캐시 (Caffeine + Mock Redis)** | L1 Caffeine(로컬·최속) + L2 Mock Redis(공유·직렬화). 실 환경은 포트 교체만으로 Redis 연동 |
| **ArchUnit** | 아키텍처 규칙 + 코루틴 금지 + CB 누락을 코드로 검증 |
| **Ktlint** | 코드 포맷·스타일 자동 강제 (Detekt는 GA 호환 버전 부재로 미채택) |
| **Kotest + MockK** | Kotlin 친화적인 테스트 프레임워크 |

**코루틴을 쓰지 않은 이유**: 이 프로젝트는 스레드 기반 설계를 채택했다. Virtual Thread가 코루틴의 블로킹 문제를 해결하므로 굳이 코루틴을 추가할 이유가 없다. → 자세한 이유: [ADR-002](adr/002-thread-based-not-coroutines.md)

---

## Spring Boot 4.x / Java 25에서 겪은 실제 이슈들

| 이슈 | 원인 | 해결 |
|---|---|---|
| `@WebMvcTest` 클래스 없음 | Spring Boot 4.0에서 제거됨 | `MockMvcBuilders.standaloneSetup()` 사용 |
| Jackson 설정 오류 | Jackson 3.0에서 `SerializationFeature` enum 변경 | YAML 설정 제거, 자동 설정에 위임 |
| ArchUnit 동작 안 함 | Java 25 바이트코드를 ASM이 못 읽음 | JVM 컴파일 타깃을 21로 낮춤 |
| MDC가 Virtual Thread에서 사라짐 | ThreadLocal은 Virtual Thread에 자동 상속 안 됨 | MDC 캡처 후 명시적으로 전달하는 래퍼 작성 |
| 앱 기동 실패 (resilience4j) | Spring Boot 4.x는 `resilience4j-spring-boot3` 거부 | `resilience4j-spring-boot4` 아티팩트로 교체 |
| Kotest 6.x 컨텍스트 테스트 깨짐 | `kotest-extensions-spring`이 6.x와 비호환 | 제거 후 `@ExtendWith(SpringExtension)` 사용 |

---

## 테스트 현황

총 **133개 테스트**, 전체 통과 (`HexagonalArchitectureTest`의 ArchUnit 규칙 6개 포함).

| 테스트 클래스 | 개수 | 무엇을 검증하나 |
|---|---:|---|
| `CacheInvalidationServiceTest` | 17 | 캐시 무효화·재갱신·이벤트 발행·예외 격리 |
| `InvestmentDashboardServiceTest` | 16 | 서비스 로직, 병렬 실행, 예외 분류, 도메인 검증 |
| `CacheKeyVersionGeneratorTest` | 16 | 클래스 구조 해시, 필드 변경 감지, 순환참조 |
| `TwoTierCacheTest` | 13 | L1+L2 이중 캐시·직렬화·이중 무효화·Pub/Sub |
| `VirtualThreadIsolationTest` | 13 | 스레드 이름·격리·Virtual Thread·병렬성 |
| `InvestmentDashboardControllerTest` | 7 | 집계 HTTP 요청/응답, 헤더, 금액 계산 |
| `InvestmentResourceControllerTest` | 7 | 도메인별 엔드포인트·속성별 Cache-Control·실패 상태(503/504/429) |
| `HealthControllerTest` | 7 | Startup·Readiness·Liveness Probe |
| `WarmupServiceTest` | 7 | 웜업 완료 상태·다중 Warmer·실패 허용 |
| `CacheAdminControllerTest` | 6 | 캐시 목록·무효화·재갱신 REST |
| `HexagonalArchitectureTest` | 6 | 아키텍처 경계 + CB 누락 + 코루틴 금지 자동 감지 |
| `DashboardWarmerTest` | 5 | 대시보드 사전 호출·예외 허용 |
| `StartupReadyTrackerTest` | 5 | probe gap 메트릭 (NaN sentinel) |
| `CachingResilientAdapterTest` | 4 | 캐시 히트/미스, 독립 키 |
| `LocalCacheSeederTest` | 2 | local 프로파일 추천 캐시 데모 시딩 |
| `GlobalExceptionHandlerTest` | 1 | 에러 응답 형식 (RFC 7807) |
| `InvestHubApplicationTests` | 1 | Spring 컨텍스트 정상 로드 |

---

## API 한 줄 정리

```
GET /api/v1/investment/dashboard
헤더: X-User-Id: {사용자ID}

응답: 계좌정보 + 해외주식 + 추천상품 (각 섹션이 성공/실패를 독립적으로 표시)
```

모든 요청에 UUID 기반 `X-Request-Id`가 응답 헤더로 돌아온다. 서버 로그에서 이 ID로 하나의 요청 전체 흐름을 추적할 수 있다.

> 도메인별 엔드포인트(`/assets`·`/foreign-stocks`·`/recommendations`)와 캐시 Admin·Health Probe 포함 전체 명세 → [docs/api/api-reference.md](api/api-reference.md)

---

## AI로 개발한 프로젝트

이 프로젝트는 **Claude Code**를 주 개발자로 사용해 **PRD → 설계 → 개발+테스트 → 비판적 검토 → 사람 리뷰** 5단계로 구현했다. 1~4단계는 AI가 자동 수행하고, 사람은 5단계(리뷰·승인)만 한다.

AI가 세션 간 일관성을 유지하고 실수해도 안전하도록 다음 장치를 깔았다:

- **`CLAUDE.md`** — AI가 매 세션 자동으로 읽는 규칙·제약. 세션이 끊겨도 같은 결정을 유지한다.
- **ADR** — "왜 이 결정을 했는지" 기록. AI가 나중에 번복하지 못하게 한다.
- **ArchUnit** — 아키텍처 위반·코루틴·CB 누락을 빌드에서 자동 차단(안전망).
- **Skill 자동화** — `/add-datasource`로 PRD를 넣으면 약 10개 파일이 자동 생성, `/self-review`가 자동 검토.
- **CI** — GitHub Actions가 최종 검증. 실패하면 Merge 불가.

> 5단계 워크플로우의 전체 정의와 실무 사용법 → [docs/ai/ai-dev-workflow.md](ai/ai-dev-workflow.md) · [docs/ai/ai-dev-guide.md](ai/ai-dev-guide.md)

---

## 예상 Q&A — 설계 의사결정 리뷰

> 시스템 고가용성과 AI 트렌드에 관심이 많은 시니어 개발자가 물어볼 만한 질문과 답변.
> 초보자도 이해할 수 있도록 쉽게 정리했다.

### 🏗️ 아키텍처

**Q1. 왜 헥사고날 아키텍처인가요? 단순 3계층(Controller-Service-Repository)이면 안 되나요?**

이 프로젝트의 핵심은 "성격이 다른 세 데이터 소스(내부 원장·외부 제휴사·추천 엔진)를 다루는 것"입니다. 헥사고날은 **비즈니스 로직이 외부 시스템을 직접 모르게** 만듭니다. 예를 들어 제휴사 API를 gRPC에서 REST로 바꿔도, 서비스 코드는 `ForeignStockPort` 인터페이스만 알기 때문에 한 줄도 바뀌지 않습니다. 데이터 소스가 다양하고 교체 가능성이 높을수록 이 구조의 가치가 커집니다.

**Q2. 단일 모듈인데 헥사고날 경계가 실제로 지켜지나요? 결국 다 같은 모듈인데 import 한 줄이면 뚫리잖아요.**

맞습니다. 그래서 **ArchUnit 테스트로 경계를 강제**합니다. "도메인이 Spring을 import하면 빌드 실패", "서비스가 어댑터를 직접 참조하면 빌드 실패" 같은 규칙이 테스트로 박혀 있습니다. 사람이 규칙을 기억하는 게 아니라, 위반하는 순간 CI가 빨간불을 켭니다. 멀티모듈 없이도 컴파일 수준에 준하는 강제력을 얻습니다.

---

### ⚡ 고가용성 & 동시성

**Q3. 왜 코루틴이 아니라 Virtual Thread인가요? 코틀린이면 코루틴이 자연스럽지 않나요?**

Virtual Thread는 **블로킹 코드를 그대로 쓰면서** 수만 개의 동시 요청을 처리합니다. 코루틴은 강력하지만 `suspend` 함수가 코드 전체에 전염되고, 블로킹 라이브러리(JDBC 등)와 섞이면 오히려 위험합니다. Java 25 Virtual Thread는 코루틴이 풀려던 문제(스레드가 블로킹에 묶이는 것)를 JVM 레벨에서 해결합니다. 그래서 추가 패러다임 없이 같은 효과를 얻습니다. 이 결정은 `ADR-002`에 기록되어 있고, ArchUnit의 `noCoroutineUsage` 규칙이 `kotlinx.coroutines` import를 빌드에서 막습니다.

**Q4. "하나가 망가져도 나머지는 보여준다"고 했는데, 제휴사가 30초씩 응답을 안 하면 전체 응답도 30초 걸리는 것 아닌가요?**

아닙니다. 세 가지 방어막이 있습니다. ① **TimeLimiter**가 제휴사 호출을 3초에서 끊습니다. ② **Circuit Breaker**가 제휴사 실패가 쌓이면 아예 호출을 차단(즉시 실패)합니다. ③ **Bulkhead**가 동시에 제휴사로 가는 요청 수를 제한해 다른 요청까지 말려들지 않게 합니다. 그래서 제휴사가 죽어도 전체 응답은 정상 섹션 기준 수백 ms 안에 돌아오고, 죽은 섹션만 `FAILURE`로 표시됩니다.

**Q5. TPS 10만을 가정했다는데, Bulkhead를 50으로 두면 99%가 거부되지 않나요?**

초기엔 그랬습니다. 그래서 Little's Law(동시 처리 수 = 초당 요청 × 평균 응답시간)로 다시 계산했습니다. 인스턴스당 1.5만 TPS 기준, 제휴사는 평균 240ms이므로 1.5만 × 0.24 ≈ 3,600 → 4,000으로 잡았습니다. 또한 글로벌 **Rate Limiter**(인스턴스당 1.5만 TPS)로 버스트를 입구에서 막아 메모리 폭증과 GC 압박을 방지합니다. 한도 초과는 즉시 429를 돌려줍니다.

**Q6. 새 Pod가 떴을 때 첫 요청들이 느린 문제(cold start)는 어떻게 다루나요?**

`WarmupService`가 `ApplicationReadyEvent`에서 대시보드를 미리 3번 호출합니다. 이걸로 JIT 컴파일을 유도하고 추천 캐시를 미리 채웁니다. 그리고 **Startup Probe 전용 엔드포인트**(`/health/startup`)가 웜업 완료 전까지 503을 돌려주므로, K8s는 웜업이 끝난 Pod에만 트래픽을 보냅니다. 추가로 "웜업 완료 → 첫 트래픽 도착"까지의 시간차를 메트릭으로 측정해 배포 전략 튜닝에 씁니다.

---

### 🗄️ 캐시

**Q7. 캐시에 저장한 객체에 필드를 하나 추가하면 어떻게 되나요? 운영에서 자주 사고나는 부분인데요.**

Redis 같은 영속 캐시에서 흔한 사고죠. 구 포맷 JSON과 새 클래스가 충돌해 역직렬화가 깨집니다. 보통은 개발자가 `cache:v2`처럼 수동으로 버전을 올리는데, 이게 휴먼 에러의 원천입니다. 이 프로젝트는 `CacheKeyVersionGenerator`가 **클래스 구조를 SHA-256으로 해시해 키에 자동으로 박습니다**(`recommendations:v7a0fe702`). 필드가 바뀌면 해시가 자동으로 바뀌어 새 키를 쓰므로, 사람이 버전을 올릴 필요가 없습니다.

**Q8. 추천 모델을 긴급 교체했습니다. TTL 5분을 기다려야 새 추천이 나가나요?**

아니요. `/admin/cache/recommendations/{userId}` (DELETE)로 즉시 무효화하거나, `.../refresh` (POST)로 **무효화와 동시에 재갱신**할 수 있습니다. 재갱신은 캐시를 비운 직후 데이터 소스를 능동적으로 호출해 미리 채우므로, 사용자가 캐시 미스로 인한 지연조차 겪지 않습니다.

**Q9. Pod가 10개인데 한 Pod에서 캐시를 비워도 나머지 9개는 그대로 아닌가요?**

두 단계로 해결합니다. ① **L2(공유 Mock Redis)** 는 모든 Pod가 함께 보는 계층이라, 무효화 시 한 번만 비우면 전 Pod에 즉시 반영됩니다. ② 각 Pod의 **L1(로컬 Caffeine)** 은 `MockRedisCacheEventPublisher`가 발행하는 Pub/Sub 이벤트를 `L1EvictionSubscriber`가 받아 비웁니다. 즉 무효화 한 번에 L1·L2가 모두 정리됩니다. Redis는 지금 인메모리 Mock(`MockRedisStore`)으로 동작하며, 실 환경에선 `DistributedCacheStore` 포트의 Lettuce 구현만 끼우면 코어 코드는 한 줄도 안 바뀝니다.

---

### 🤖 AI 기반 개발 & 협업

**Q10. AI로 개발했다는데, 여러 명이 같이 작업하면 코드 스타일이 제각각이지 않나요?**

그래서 강제 장치를 깔았습니다. **Ktlint**가 포맷·스타일을, **ArchUnit**이 아키텍처 규칙·코루틴 금지를 검사합니다. 둘 다 CI에서 실패하면 Merge가 막힙니다. 사람이든 AI든 같은 규칙을 통과해야만 코드가 들어갑니다. 또 `CLAUDE.md`에 프로젝트 규칙을 적어두면 AI가 다음 세션에서도 같은 결정을 유지합니다.

**Q11. AI가 실수로 외부 호출 어댑터에 Circuit Breaker 다는 걸 깜빡하면요?**

그것도 ArchUnit으로 막습니다. "`adapter.out`의 외부 호출 어댑터는 반드시 `ResilientAdapter`를 상속해야 한다"는 규칙이 있어서, CB·Bulkhead·TimeLimiter 없이 어댑터를 만들면 빌드가 실패합니다. 휴먼 에러(또는 AI 에러)를 구조적으로 차단하는 것이 이 프로젝트의 일관된 철학입니다.

**Q12. AI가 코드를 만든 뒤 검토 없이 바로 사람한테 넘기면 리뷰 부담이 크지 않나요?**

그래서 사람 리뷰 직전에 **④ 비판적 검토 단계**를 넣었습니다. AI가 `/self-review` 커맨드로 안전성·아키텍처·휴먼에러·테스트 체크리스트를 스스로 점검하고, 위반은 즉시 고친 뒤 "수정한 것 / 사람 판단이 필요한 것"을 보고서로 만듭니다. 사람은 이 보고서의 "판단 필요 항목"만 봅니다. 기계가 잡을 수 있는 건 기계가 다 잡고 넘깁니다. (전체 흐름: `docs/ai/ai-dev-workflow.md`)

**Q13. 새 데이터 소스(예: 국내 채권)를 추가하려면 얼마나 걸리나요?**

`/add-datasource` 명령어에 PRD(어떤 데이터인지 양식)를 넣으면, 도메인·포트·어댑터·입력 유스케이스·도메인 리소스 컨트롤러·테스트까지 약 10개 파일이 한 번에 생성되고, `/self-review`가 자동 검토합니다. 사람은 PRD 작성(5분)과 최종 리뷰만 합니다. 반복 작업을 AI가, 판단을 사람이 맡는 구조입니다.

---

### 🧪 검증 & 안전성

**Q14. 테스트 133개가 다 의미 있는 건가요, 숫자 채우기는 아닌가요?**

레이어별로 책임이 다릅니다. 단위 테스트(서비스 로직·도메인 검증), 통합 성격 테스트(캐시 히트/미스, Executor 격리), HTTP 계층(MockMvc), 그리고 **아키텍처 테스트(ArchUnit)**가 있습니다. 특히 "제휴사가 죽어도 나머지는 SUCCESS"나 "필드 추가 시 캐시 키 자동 변경" 같은 핵심 시나리오가 테스트로 박혀 있어, 리팩터링 시 회귀를 잡아줍니다.

**Q15. 라이브러리 버전은 어떻게 관리하나요? 최신을 쓰나요?**

**GA(정식 릴리즈)만 씁니다.** RC·alpha·beta·SNAPSHOT은 금지입니다. 실제로 한때 Spring Boot 4.1.0-RC1을 썼다가 RC라서 GA인 **4.0.6**으로 내렸고, Detekt는 Kotlin 2.3.21을 지원하는 게 alpha뿐이라 **아예 채택하지 않고** 그 역할을 GA 도구(Ktlint·ArchUnit)로 대체했습니다. "최신 기능"보다 "검증된 안정성"이 대고객 금융 서비스의 우선순위입니다. 이 원칙은 `CLAUDE.md` 절대 금지 사항에 박아두고, `/self-review`가 새 의존성의 GA 여부를 점검하므로 사람·AI 누구도 실수로 RC를 넣을 수 없습니다.

**Q16. "최신 기능을 포기한다"면 기술 부채 아닌가요?**

기능을 영구히 포기하는 게 아니라 **GA가 나올 때까지 미루는 것**입니다. Detekt도 "2.x가 GA로 출시되면 재검토"라고 문서에 적어 뒀습니다. 그리고 그동안 공백을 방치하지 않고 GA 대안(ArchUnit의 코루틴 금지 규칙)으로 메웠기 때문에, 실제 품질 게이트에는 구멍이 없습니다. 안정성을 지키면서 기능 공백도 메우는 균형입니다.
