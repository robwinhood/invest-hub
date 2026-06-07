# CLAUDE.md — invest-hub AI 개발 가이드

> 이 파일은 Claude Code가 프로젝트를 열 때 자동으로 읽는 컨텍스트 파일이다.
> 코드 컨벤션이 아니라 **"AI가 세션 간 반드시 유지해야 할 결정과 제약"** 을 담는다.

---

## 프로젝트 개요

세 가지 이질적인 데이터 소스(내부 원장, 제휴사 외부 API, 추천 엔진)를 단일 엔드포인트로 통합하는 투자 서비스 대시보드 API. POC/데모 성격이므로 외부 시스템은 모두 Mock으로 구현되어 있다.

---

## 기술 스택 (변경 전 반드시 확인)

**모든 버전은 GA(정식 릴리즈)다. RC·alpha·beta·SNAPSHOT 도입 금지 (아래 절대 금지 사항 참고).**

| 항목 | 버전 | 비고 |
|---|---|---|
| Kotlin | **2.3.21** | GA |
| Spring Boot | **4.0.6** | GA. Spring Framework 7, Jackson 3.0. (4.1.x는 GA 미출시 → 쓰지 말 것) |
| JDK | 25 (Corretto) | `~/.jdks/corretto-25/Contents/Home` |
| JVM target | **21** | ArchUnit ASM 호환성 때문. 변경하지 말 것 |
| Resilience4j | **2.4.0** | Spring Boot 4.x → `resilience4j-spring-boot4` artifact 사용 (3.x용 불가) |
| Kotest | **6.1.11** | `kotest-extensions-spring` 미사용 (6.x 비호환) |
| MockK | **1.14.11** | |
| ArchUnit | **1.4.2** | 아키텍처 경계 + 코루틴 금지 + CB 누락 강제 |
| kotlin-logging | **8.0.4** | |
| Ktlint | **1.5.0** | kotlinter 5.0.1 플러그인 |
| Detekt | **미채택** | GA(1.23.8)는 Kotlin 2.3.21 비호환, 2.x는 alpha. Ktlint+ArchUnit으로 대체 |
| 빌드 도구 | Gradle 8 (Kotlin DSL) | |

---

## 빌드 · 실행 명령

```bash
export JAVA_HOME=~/.jdks/corretto-25/Contents/Home

./gradlew test          # 테스트 (133개)
./gradlew bootRun       # 로컬 서버 (포트 8080, 관리 8081) — 'local' 프로파일 자동 활성화 → 추천 캐시 데모 시딩
./gradlew build         # 전체 빌드
./gradlew formatKotlin  # 코드 포맷 자동 수정
./gradlew lintKotlin    # 포맷 검사 (CI에서 실행)
./gradlew check-all     # lintKotlin + test (PR 전 필수, 파일 수정 없음)
./gradlew fix-all       # formatKotlin + test (포맷 자동 수정)
```

---

## 절대 금지 사항 (이유 포함)

### 비-GA 버전 의존성 도입 금지
```
// 금지: RC, alpha, beta, M(milestone), SNAPSHOT
org.springframework.boot version "4.1.0-RC1"   // RC
dev.detekt version "2.0.0-alpha.3"              // alpha
```
**이유**: 대고객 금융 서비스는 검증이 끝난 GA(General Availability) 버전만 사용한다. RC/alpha는 기능 동결 단계지만 최종 검증 전이라 프로덕션에 부적합하다. 최신 기능이 GA에 없으면 **그 기능을 포기하거나 GA 대안을 찾는다** (예: Detekt 대신 ArchUnit). 새 의존성 추가 전 GitHub releases에서 `prerelease=false`를 반드시 확인할 것.

### 코루틴 사용 금지
```kotlin
// 금지
import kotlinx.coroutines.*
suspend fun foo() { ... }
async { ... }
launch { ... }
```
**이유**: 이 프로젝트는 의도적으로 Java 스레드 모델을 선택했다. Virtual Thread + CompletableFuture가 기반이다. → [ADR-002](docs/adr/002-thread-based-not-coroutines.md)

### `@WebMvcTest` 사용 금지
```kotlin
// 금지
@WebMvcTest(SomeController::class)  // Spring Boot 4.0에서 제거됨
```
**이유**: Spring Boot 4.0의 `spring-boot-test-autoconfigure`에서 web 슬라이스 테스트가 제거됐다.  
**대안**: `MockMvcBuilders.standaloneSetup(controller).build()` 사용.

### `spring.jackson.*` YAML 프로퍼티 사용 금지
```yaml
# 금지
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false  # Spring Boot 4.0 Jackson 3.0 비호환
```
**이유**: Spring Boot 4.0은 Jackson 3.0(`tools.jackson`)을 사용하며 `SerializationFeature` enum 이름이 변경됐다.

### `com.fasterxml.jackson.datatype.jsr310.JavaTimeModule` import 금지
```kotlin
// 금지
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
```
**이유**: `jackson-datatype-jsr310` 의존성이 없다. Spring Boot 4.0이 Jackson 3.0을 자동 설정하므로 별도 ObjectMapper Bean 불필요.

### `domain` 패키지에 Spring/외부 라이브러리 import 금지
```kotlin
// 금지 — domain 패키지 내에서
import org.springframework.*
import io.github.resilience4j.*
```
**이유**: 헥사고날 아키텍처 원칙. ArchUnit이 빌드에서 강제한다. 위반 시 `./gradlew test` 실패.

### `application` 패키지에서 `adapter` 직접 참조 금지
**이유**: 의존성 역전 원칙. 서비스는 포트 인터페이스만 알아야 한다. ArchUnit 강제.

---

## 아키텍처 원칙

### 헥사고날 레이어

```
domain         ← 순수 Kotlin. 외부 의존 없음. require()로 불변식 강제.
application    ← 유스케이스 + 포트 인터페이스 + 서비스. domain만 참조.
adapter/in/web       ← HTTP 진입점. Filter, Controller, DTO.
adapter/in/lifecycle ← Spring 이벤트 진입점. WarmupInvoker, DashboardWarmer, StartupReadyTracker.
adapter/out          ← 외부 시스템 연동. ResilientAdapter 또는 CachingResilientAdapter 상속.
adapter/out/cache    ← 캐시 인프라: TwoTierCache(L1+L2), MockRedisStore(L2), 이벤트 발행·구독·재갱신. CB 패턴 불필요.
config         ← Spring Bean 설정. CacheConfig, CacheKeyVersionGenerator, VirtualThreadConfig.
```

### 새 데이터 소스 추가 체크리스트

1. `domain/` — 도메인 모델 + `init { require(...) }` 검증
2. `application/port/output/` — Out-Port 인터페이스 정의
3. `adapter/out/` — `ResilientAdapter` 또는 `CachingResilientAdapter` 상속
4. `application.yml` — `resilience4j.circuitbreaker/bulkhead/timelimiter.instances.{portName}` 추가
5. `domain/dashboard/InvestmentDashboard.kt` — Sealed Result 타입 추가
6. `application/service/InvestmentDashboardService.kt` — `supplyAsyncWithMdc()` 병렬 조회 추가
7. `adapter/in/web/dto/InvestmentDashboardResponse.kt` — Section 응답 DTO 추가

**실시간 데이터** (해외 주식 등) → `ResilientAdapter<T>` 상속  
**저실시간 데이터** (추천 상품 등) → `CachingResilientAdapter<T>` 상속 (캐시 자동 보장)

---

## 동시성 패턴 (반드시 준수)

```kotlin
// 올바른 패턴 — MDC 컨텍스트를 Virtual Thread에 명시적 전파
val assetFuture = supplyAsyncWithMdc { fetchAssetSummary(userId) }
val stockFuture = supplyAsyncWithMdc { fetchForeignStockPortfolio(userId) }

// 금지 — MDC가 Virtual Thread에 전파되지 않음
val assetFuture = CompletableFuture.supplyAsync({ fetch(userId) }, executor)
```

**이유**: `ThreadLocal` 기반 MDC는 Virtual Thread에 자동 상속되지 않는다. `supplyAsyncWithMdc()`가 이를 처리한다.

---

## 테스트 작성 원칙

### 사용하는 것
- Kotest `DescribeSpec` 스타일
- MockK (`io.mockk:mockk`)
- `MockMvcBuilders.standaloneSetup()` (Spring 컨텍스트 없이 Controller 테스트)
- ArchUnit (`HexagonalArchitectureTest` — 아키텍처 경계 강제)

### 사용하지 않는 것
- `@WebMvcTest` (Spring Boot 4.0에서 제거됨)
- Mockito (MockK를 사용)
- `@MockkBean` + SpringExtension (standaloneSetup으로 대체)

### 도메인 테스트 필수 패턴
```kotlin
it("음수 잔액으로 생성하면 IllegalArgumentException이 발생한다") {
    shouldThrow<IllegalArgumentException> {
        SavingsAccount(accountId = "ACC-001", ..., balance = BigDecimal("-1"))
    }
}
```

### 병렬 실행 검증 패턴
```kotlin
it("세 포트를 각자 다른 가상 스레드에서 호출한다") {
    val callerThreadIds = ConcurrentHashMap.newKeySet<Long>()
    // threadId를 수집해 size == 3 검증
}
```

---

## Resilience4j 설정 규칙

데코레이션 순서: **CB → Bulkhead → TimeLimiter** (이 순서 변경 금지)

- `BulkheadRegistry.bulkhead()` = Semaphore Bulkhead (기본값, 변경 금지)
- `BulkheadRegistry.threadPoolBulkhead()` 사용 금지 — Virtual Thread 환경에서 의미 없음

데이터 소스별 신뢰도:
- 내부 원장: CB 50%, Bulkhead 1000, TL 2s
- 제휴사(외부): CB **40%**, Bulkhead **4000**, TL **3s** (가장 엄격)
- 추천 엔진: CB **60%**, Bulkhead 2000, TL 1.5s (가장 관대)

---

## 알려진 Spring Boot 4.0 특이사항

| 현상 | 원인 | 대처 |
|---|---|---|
| `@WebMvcTest` 클래스 없음 | Spring Boot 4.0에서 web slice 제거 | `standaloneSetup()` 사용 |
| `JavaTimeModule` 못 찾음 | Jackson 3.0 전환, `jackson-datatype-jsr310` 미포함 | Jackson 자동 설정에 위임 |
| `spring.jackson.serialization.*` 오류 | Jackson 3.0 `SerializationFeature` enum 변경 | YAML에서 해당 설정 제거 |
| ArchUnit 클래스 파싱 실패 | JVM target 25 bytecode → ASM 9.6 미지원 | JVM target 21 고정 유지 |

---

## Executor 규칙 (반드시 준수)

어댑터별로 **전용 executor**를 주입받아야 한다. 단일 공유 executor 사용 금지.

| Qualifier | 용도 | 스레드 이름 |
|---|---|---|
| `accountExecutor` | InternalAccountAdapter 전용 | `account-vt-N` |
| `partnerExecutor` | PartnerForeignStockAdapter 전용 | `partner-vt-N` |
| `recommendationExecutor` | RecommendationEngineAdapter 전용 | `recommendation-vt-N` |
| `serviceExecutor` | InvestmentDashboardService Future 제출 전용 | `service-vt-N` |

새 어댑터 추가 시 → `VirtualThreadConfig`에 전용 executor 빈을 추가하고 `@Qualifier`로 주입한다.

---

## 코드 품질 규칙 (다중 개발자/AI 환경)

### Ktlint (kotlinter 5.0.1)
- 코드 작성 후 반드시 `./gradlew formatKotlin` 실행 후 커밋한다.
- CI에서 `lintKotlin`이 실패하면 PR Merge 불가.
- 규칙 커스터마이징은 `.editorconfig`에서 한다.

```bash
# 포맷 자동 수정
./gradlew formatKotlin

# 포맷 검사 (수정 없음 — CI용)
./gradlew lintKotlin
```

### Detekt 미채택 (GA 안전 원칙)
- Detekt GA(1.23.8)는 Kotlin 2.0.x 컴파일 → Kotlin 2.3.21과 바이너리 비호환.
- Kotlin 2.3.21 지원 버전(2.0.0-alpha.x)은 alpha → 비-GA 금지 원칙 위배.
- **결정**: Detekt를 쓰지 않는다. 정적 분석 역할은 GA 도구로 분담:
  - **Ktlint** — 포맷·스타일·import·wildcard
  - **ArchUnit** — 아키텍처 경계 + 코루틴 금지(ADR-002) + CB 누락 방지
- Detekt 2.x가 GA로 출시되면 재검토.

### 코루틴 금지는 ArchUnit이 강제
`HexagonalArchitectureTest.noCoroutineUsage` 규칙이 `kotlinx.coroutines` import를 빌드에서 차단한다.

### 태스크 구분 — 파일 수정 여부
```bash
./gradlew fix-all    # formatKotlin + test   ← 파일 수정됨. 개발 중 사용.
./gradlew check-all  # lintKotlin + test      ← 파일 수정 없음. PR 전 필수.
```

## 개발 워크플로우 (반드시 따를 것)

모든 기능 개발은 **PRD → 설계 → 개발+테스트 → 비판적 검토 → 사람 리뷰** 5단계를 따른다.
1~4단계는 AI가 자동 수행하고, 5단계만 사람이 한다. 전체 정의: `docs/ai/ai-dev-workflow.md`

- ③ 개발: 새 데이터 소스는 `/add-datasource` skill 사용
- ④ 검토: **코드 작성 후 반드시 `/self-review` 실행** 후 사람 리뷰로 넘긴다 (건너뛰지 말 것)
- **`/ship`**: PRD를 주면 **①요구사항분석 → ②설계 → ③개발+테스트 → ④자가검토 → check-all → 커밋·푸시 → ⑤PR 생성**까지 한 번에(적응형 — 이미 끝난 단계는 건너뜀). 머지·배포는 사람 몫이며, "머지까지"를 명시하면 CI 통과 확인 후 자동 머지한다. (상세·예시: 아래 "자동화 Skill — PRD에서 PR까지 (/ship)")

## CI / PR 프로세스

- `.github/workflows/ci.yml` — Lint Check → Build & Test 순서로 자동 실행
- `.github/pull_request_template.md` — PR 생성 시 자동으로 리뷰 체크리스트 표시 (자가 검토 보고서 첨부)
- 코드 변경 후 반드시 `CHANGELOG.md`에 항목을 추가한다

## 에러 처리

- `GlobalExceptionHandler` — Spring이 직접 던지는 400/500 오류를 RFC 7807 형식으로 통일
- 비즈니스 실패(데이터 소스 조회 실패)는 `Sealed Result`가 처리 — `GlobalExceptionHandler` 미개입

## 캐시 규칙 (반드시 준수)

- **이중 캐시 구조**: 추천 캐시는 `TwoTierCache` = L1(Caffeine) + L2(Mock Redis, `MockRedisStore`)다. `org.springframework.cache.Cache`를 구현하므로 `CachingResilientAdapter`·무효화 서비스는 변경 없이 동작한다.
  - L2는 `DistributedCacheStore` 포트 뒤에 있다. 실 Redis 전환 시 이 포트의 Lettuce 구현만 추가하면 된다 (Mock은 GA 정책상 embedded-redis 대신 인메모리 구현).
  - L2는 **직렬화 저장**(Jackson 3)이므로 캐시 키 자동 버전이 실효를 갖는다.
- **캐시 이름은 직접 문자열로 쓰지 말 것.** `CacheKeyVersionGenerator.versionedName(baseName, KClass)`로 생성한다.
  - 클래스 구조 해시가 키에 자동 삽입되어 필드 변경 시 키가 자동으로 바뀐다 (L2 직렬화 충돌 방지).
- 캐시 무효화·재갱신은 `CacheInvalidationService`를 통한다. 어댑터에서 직접 `cache.evict()` 호출 금지.
  - 무효화는 **L1·L2 모두** 비우고, `MockRedisCacheEventPublisher` → `L1EvictionSubscriber` Pub/Sub으로 타 Pod L1까지 전파한다.
- 분산 무효화 발행자는 현재 `MockRedisCacheEventPublisher`(빈 이름 `redisCacheEventPublisher`)가 활성. `NoOpCacheEventPublisher`는 `@ConditionalOnMissingBean`으로 자동 비활성화된다.
- 새 캐시에 즉시 재갱신이 필요하면 `CacheRefreshStrategy`를 구현하고 `supports()`로 대상 캐시를 선언한다.
- **이름(컨테이너) vs 키(엔트리) 구분**: `GET /admin/cache`의 `name`(예: `recommendations:v7a0fe702`)은 캐시 이름이고 `:v…`는 클래스 구조 해시 버전이다(키 아님). 그 안의 `keys`(예: `user-001`)가 evict 대상. 엔트리를 evict해도 캐시 이름 목록은 변하지 않는다. 키 열거는 `CacheKeyEnumerable`(TwoTierCache가 구현)이 제공한다.

## 프로젝트 제약 사항

- 패키지 루트: `com.investhub`
- 외부 시스템은 모두 Mock으로 구현 (실제 HTTP 클라이언트 불필요)
- README, HELP.md, docs/adr/, CHANGELOG.md 변경 시 내용이 실제 코드와 일치하는지 확인

---

## 자동화 Skill — 새 데이터 소스 추가

투자 대시보드에 새 섹션을 추가할 때는 수동으로 약 10개 파일을 만들 필요 없다.
`/add-datasource` 명령어에 PRD를 붙여넣으면 자동으로 생성된다.

**PRD 작성법**: `docs/template/prd-datasource-template.md` 참고  
**Skill 정의**: `.claude/commands/add-datasource.md`

```
# 사용 예시
/add-datasource

## 데이터 소스 이름
국내 ETF 포트폴리오

## 실시간성
낮음 (10분 단위)
캐시 TTL: 10분

... (템플릿 나머지 채워서)
```

---

## 자동화 Skill — PRD에서 PR까지 (`/ship`)

`/ship`은 **전체 개발 파이프라인**을 자동화한다. PRD를 주면 **①요구사항분석 → ②설계(필요 시 ADR) → ③개발+테스트 → ④자가검토 → check-all → 커밋·푸시 → ⑤PR 생성**까지 흐른다.
**적응형**: 이미 끝난 단계는 건너뛴다(코드가 다 됐으면 ④자가검토부터 = 마무리 모드). PR 본문의 "AI 자동 단계 확인(①~④)"과 자가 검토 보고서까지 자동으로 채운다. 리뷰·승인·머지(⑤의 본질)는 사람이 한다.

**Skill 정의**: `.claude/commands/ship.md`

```
# 1) PRD/요구사항을 주면 — 요구사항 분석부터 전체 파이프라인 → PR
/ship 국내 ETF 섹션 추가 (저실시간, 캐시 10분). PRD는 docs/prd-etf.md

# 2) 코드가 이미 다 됐으면 — 자가검토→검증→커밋·푸시→PR (마무리 모드)
/ship

# 3) 관련 이슈를 엮을 때
/ship #42 국내 ETF 섹션 추가

# 4) 머지까지 자동으로 (CI 통과 확인 후 squash 머지)
/ship ... 머지까지 해줘
```

**가드레일** (어기면 중단):
- `main`/`develop` 등 보호 브랜치에는 직접 커밋하지 않는다. 보호 브랜치 위라면 커밋 전에 피처 브랜치를 자동 생성한다.
- `gh` 미설치·미인증이면 중단한다 (최초 1회 `gh auth login` 필요).
- `check-all` 실패 시 PR을 만들지 않는다. 머지는 명시 지시가 없으면 사람이 한다.

> 참고: 이 저장소 remote가 커스텀 SSH 별칭(`github-robwinhood`)이라, `gh` 명령은 `-R robwinhood/invest-hub`로 저장소를 명시한다.

---

## 설계 결정 문서 (ADR)

주요 설계 결정의 배경은 `docs/adr/`에 기록되어 있다. 코드 변경 전 반드시 확인할 것.

- [ADR-001](docs/adr/001-spring-mvc-over-webflux.md) — Spring MVC + Virtual Threads 선택
- [ADR-002](docs/adr/002-thread-based-not-coroutines.md) — Kotlin 코루틴 미사용
- [ADR-003](docs/adr/003-resilient-adapter-pattern.md) — ResilientAdapter 추상 클래스
- [ADR-004](docs/adr/004-caching-resilient-adapter.md) — CachingResilientAdapter 구조적 캐시 보장
- [ADR-005](docs/adr/005-single-module.md) — 단일 모듈 선택
- [ADR-006](docs/adr/006-partial-success-sealed-class.md) — Partial Success 패턴
- [ADR-007](docs/adr/007-cache-key-versioning-and-invalidation.md) — 캐시 키 자동 버전 + 무효화·재갱신
- [ADR-008](docs/adr/008-aggregate-plus-resource-endpoints.md) — 집계 + 도메인별 리소스 엔드포인트(하이브리드 API) + 속성별 Cache-Control
