# add-datasource — 새 데이터 소스 섹션 추가

invest-hub 대시보드에 새로운 데이터 소스 섹션을 추가한다.
아래 PRD를 읽고 헥사고날 아키텍처 패턴에 맞게 모든 파일을 자동으로 생성한다.

---

## 입력된 PRD

$ARGUMENTS

---

## 실행 전 준수 사항 (반드시 먼저 읽을 것)

1. `CLAUDE.md`를 읽어 프로젝트 규칙과 절대 금지 사항을 확인한다.
2. 기존 어댑터 패턴 파악을 위해 다음 파일들을 읽는다:
   - `src/main/kotlin/com/investhub/adapter/out/internal/InternalAccountAdapter.kt` (ResilientAdapter 예시)
   - `src/main/kotlin/com/investhub/adapter/out/recommendation/RecommendationEngineAdapter.kt` (CachingResilientAdapter 예시)
   - `src/main/kotlin/com/investhub/domain/dashboard/InvestmentDashboard.kt` (Sealed Result 패턴)
   - `src/main/kotlin/com/investhub/application/service/InvestmentDashboardService.kt` (유스케이스 구현 + 집계 재사용 패턴)
   - `src/main/kotlin/com/investhub/application/port/input/GetAssetSummaryUseCase.kt` (입력 유스케이스 패턴)
   - `src/main/kotlin/com/investhub/adapter/in/web/AssetController.kt` (도메인 단독 엔드포인트 + 속성별 Cache-Control 패턴)
   - `src/main/kotlin/com/investhub/adapter/in/web/SectionHttpStatus.kt` (FailureReason → HTTP 상태 매핑)
   - `src/main/kotlin/com/investhub/adapter/in/web/dto/InvestmentDashboardResponse.kt` (섹션 DTO 패턴)
   - `src/main/resources/application.yml` (Resilience4j 설정 구조 파악)

---

## PRD 해석 기준

PRD에서 다음 정보를 추출한다. 명시되지 않은 항목은 아래 기본값을 사용한다.

| PRD 항목 | 추출 목적 | 기본값 |
|---|---|---|
| **이름** | 클래스명, 주석에 사용 | 필수 입력 |
| **섹션 필드명** | JSON 응답 필드명 (camelCase) | 이름에서 자동 유도 |
| **포트 이름** | application.yml 키, portName 값 (kebab-case) | 이름에서 자동 유도 |
| **실시간성** | `높음` → `ResilientAdapter`, `낮음` → `CachingResilientAdapter` | 필수 입력 |
| **캐시 TTL** | 실시간성이 낮을 때만 적용 (분 단위) | 5분 |
| **CB 실패율 임계** | circuitbreaker failure-rate-threshold | 50% |
| **Bulkhead 최대 동시** | bulkhead max-concurrent-calls | 80 |
| **Timeout** | timelimiter timeout-duration | 2s |
| **도메인 모델 필드** | 모델 클래스 생성에 사용 | 필수 입력 |
| **필드 검증 조건** | `init { require(...) }` 생성에 사용 | 명시된 것만 |
| **Mock 데이터** | 어댑터의 buildMock 메서드 | 최소 2개 제공 |

---

## 생성 순서 (반드시 이 순서를 지킨다)

### STEP 1 — 도메인 모델

**경로**: `src/main/kotlin/com/investhub/domain/{섹션명}/`

- PRD의 도메인 모델 필드를 `data class`로 구현한다.
- 검증 조건이 있는 필드는 `init { require(...) }` 블록을 추가한다.
- `domain` 패키지에는 Spring, Resilience4j import가 없어야 한다 (CLAUDE.md 규칙).
- 포트폴리오 집계 모델과 개별 보유 모델을 분리한다 (예: `XxxPortfolio`, `XxxHolding`).

```kotlin
// 패턴 예시
data class DomesticBondPortfolio(
    val totalValueInKrw: BigDecimal,
    val holdingList: List<DomesticBondHolding>,
) {
    init {
        require(totalValueInKrw >= BigDecimal.ZERO) { "총 평가액은 0 이상이어야 합니다: $totalValueInKrw" }
    }
}

data class DomesticBondHolding(
    val bondName: String,
    // ... 나머지 필드
) {
    init {
        require(bondName.isNotBlank()) { "채권명은 비어있을 수 없습니다" }
        // ... PRD에 명시된 검증 조건
    }
}
```

### STEP 2 — Output Port 인터페이스

**경로**: `src/main/kotlin/com/investhub/application/port/output/`

```kotlin
// 패턴
interface DomesticBondPort {
    fun getPortfolio(userId: String): DomesticBondPortfolio
}
```

### STEP 3 — 어댑터 구현

**경로**: `src/main/kotlin/com/investhub/adapter/out/{섹션명}/`

- **실시간성 높음** → `ResilientAdapter<T>` 상속, `execute { callApi(userId) }` 패턴
- **실시간성 낮음** → `CachingResilientAdapter<T>` 상속, `executeWithCache(userId)` 패턴

```kotlin
// CachingResilientAdapter 패턴 (실시간성 낮음)
@Component
class DomesticBondAdapter(
    circuitBreakerRegistry: CircuitBreakerRegistry,
    bulkheadRegistry: BulkheadRegistry,
    timeLimiterRegistry: TimeLimiterRegistry,
    @Qualifier("virtualThreadExecutor") executor: ExecutorService,
    @Qualifier("{포트명}Cache") cache: Cache,  // CacheConfig에 추가 필요
) : CachingResilientAdapter<DomesticBondPortfolio>(..., cache), DomesticBondPort {
    override val portName = "{포트-이름}"
    override fun getPortfolio(userId: String): DomesticBondPortfolio = executeWithCache(userId)
    override fun callUncached(userId: String): DomesticBondPortfolio {
        Thread.sleep(Random.nextLong(30, 150))
        log.debug { "{이름} 조회 완료" }
        return buildMockPortfolio()
    }
    private fun buildMockPortfolio(): DomesticBondPortfolio { /* PRD의 Mock 데이터 */ }
}
```

**CachingResilientAdapter인 경우**: `src/main/kotlin/com/investhub/config/CacheConfig.kt`에 캐시 빈을 추가한다.

```kotlin
@Bean("{포트명}Cache")
fun {포트명}Cache(): Cache =
    CaffeineCache(
        "{포트명}",
        Caffeine.newBuilder()
            .expireAfterWrite({TTL}, TimeUnit.MINUTES)
            .maximumSize(500)
            .build(),
    )
```

### STEP 4 — application.yml Resilience4j 설정 추가

기존 `application.yml`을 읽어 `circuitbreaker`, `bulkhead`, `timelimiter` 섹션에 새 인스턴스를 추가한다.
`portName`과 YAML 키가 정확히 일치해야 한다.

```yaml
resilience4j:
  circuitbreaker:
    instances:
      {포트-이름}:                    # portName과 동일
        register-health-indicator: true
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        minimum-number-of-calls: 5
        failure-rate-threshold: {CB 실패율}
        slow-call-rate-threshold: 80
        slow-call-duration-threshold: {Timeout}
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
  bulkhead:
    instances:
      {포트-이름}:
        max-concurrent-calls: {Bulkhead 최대}
        max-wait-duration: 50ms
  timelimiter:
    instances:
      {포트-이름}:
        timeout-duration: {Timeout}s
        cancel-running-future: true
```

### STEP 5 — InvestmentDashboard.kt Sealed Result 추가

`src/main/kotlin/com/investhub/domain/dashboard/InvestmentDashboard.kt`를 읽고 수정한다.

1. `InvestmentDashboard` data class에 새 필드를 추가한다.
2. 새 Sealed Result 타입을 추가한다.

```kotlin
data class InvestmentDashboard(
    val userId: String,
    val generatedAt: LocalDateTime = LocalDateTime.now(),
    val assetSummary: AssetSummaryResult,
    val foreignStockPortfolio: ForeignStockPortfolioResult,
    val recommendedProducts: RecommendedProductsResult,
    val {섹션필드명}: {SectionName}Result,  // 추가
)

// 추가
sealed class {SectionName}Result {
    data class Success(val data: {DomainModel}) : {SectionName}Result()
    data class Failure(val reason: FailureReason) : {SectionName}Result()
}
```

### STEP 6 — Input UseCase 인터페이스 (도메인 단독 조회)

**경로**: `src/main/kotlin/com/investhub/application/port/input/Get{SectionName}UseCase.kt`

도메인별 단독 엔드포인트(ADR-008)를 위해 입력 유스케이스를 정의한다. **Sealed Result를 반환**해 집계·단독 호출이 동일한 부분 실패 처리를 공유한다.

```kotlin
interface Get{SectionName}UseCase {
    fun get{SectionName}(userId: String): {SectionName}Result
}
```

### STEP 7 — InvestmentDashboardService.kt (유스케이스 구현 + 집계 재사용)

`src/main/kotlin/com/investhub/application/service/InvestmentDashboardService.kt`를 읽고 수정한다.

1. 클래스 선언에 `Get{SectionName}UseCase`를 구현 인터페이스로 추가한다.
2. 생성자에 새 포트를 추가한다.
3. **공개** `override fun get{SectionName}()`을 추가한다(기존 도메인 유스케이스들과 동일 패턴).
4. `getDashboard()`가 이 유스케이스를 `supplyAsyncWithMdc { get{SectionName}(userId) }`로 **재사용**한다(중복 금지).

```kotlin
// 클래스 선언에 추가
class InvestmentDashboardService(
    ...,
    private val {섹션}Port: {SectionName}Port,
) : GetInvestmentDashboardUseCase,
    ...,
    Get{SectionName}UseCase {

    // 공개 유스케이스 구현 (컨트롤러 + 집계가 함께 재사용)
    override fun get{SectionName}(userId: String): {SectionName}Result =
        runCatching {
            {SectionName}Result.Success({섹션}Port.getPortfolio(userId))
        }.getOrElse { ex ->
            log.warn(ex) { "{이름} 조회 실패" }
            {SectionName}Result.Failure(ex.toFailureReason())
        }

    // getDashboard() 내 — 위 유스케이스를 병렬 재사용
    val {섹션}Future = supplyAsyncWithMdc { get{SectionName}(userId) }
    // InvestmentDashboard 생성 시: {섹션필드명} = {섹션}Future.join(),
}
```

### STEP 8 — 도메인 단독 리소스 컨트롤러 (속성별 Cache-Control)

**경로**: `src/main/kotlin/com/investhub/adapter/in/web/{SectionName}Controller.kt`

집계(`/dashboard`)와 별개로 이 도메인만 독립 조회·갱신하는 엔드포인트를 추가한다(ADR-008). 기존 `AssetController.kt`를 참고한다.

- 성공 → `200` + 섹션 DTO + **데이터 속성별 `Cache-Control`**:
  - **실시간성 높음** → `CacheControl.noStore()`
  - **실시간성 낮음** → `CacheControl.maxAge(Duration.ofMinutes({TTL})).cachePrivate()` (HTTP TTL을 캐시 TTL과 정렬)
- 실패 → `result.reason.toHttpStatus()`(기존 `SectionHttpStatus.kt` 재사용: CIRCUIT_OPEN/SERVICE_UNAVAILABLE→503, TIMEOUT→504, RESOURCE_EXHAUSTED→429) + 섹션 DTO + `noStore()`

```kotlin
@RestController
@RequestMapping("/api/v1/investment")
class {SectionName}Controller(
    private val get{SectionName}UseCase: Get{SectionName}UseCase,
) {
    @GetMapping("/{resource}")               // 예: /domestic-etfs
    fun get{SectionName}(
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<{SectionName}SectionResponse> {
        val result = get{SectionName}UseCase.get{SectionName}(userId)
        val body = {SectionName}SectionResponse.from(result)
        return when (result) {
            is {SectionName}Result.Success ->
                ResponseEntity.ok()
                    .cacheControl(/* 실시간성 높음: noStore() / 낮음: maxAge(...).cachePrivate() */)
                    .body(body)
            is {SectionName}Result.Failure ->
                ResponseEntity.status(result.reason.toHttpStatus()).cacheControl(CacheControl.noStore()).body(body)
        }
    }
}
```

### STEP 9 — InvestmentDashboardResponse.kt 섹션 DTO 추가

`src/main/kotlin/com/investhub/adapter/in/web/dto/InvestmentDashboardResponse.kt`를 읽고 수정한다.

1. `InvestmentDashboardResponse`에 새 필드 추가.
2. `from()` 팩토리 메서드에 새 섹션 추가.
3. 새 `{SectionName}SectionResponse` data class 추가.
4. 새 도메인 → DTO 변환 data class 추가.

섹션 응답은 기존 `AssetSectionResponse` 구조를 따른다:
- `status: SectionStatus`
- `failureReason: String?`
- 성공 시 데이터 필드들 (`@JsonInclude(NON_NULL)`)

### STEP 10 — 테스트 작성

#### 10-1. 도메인 검증 테스트

`InvestmentDashboardServiceTest.kt`의 "도메인 모델 — 검증" describe 블록에 추가한다.
PRD에 검증 조건이 명시된 필드마다 `shouldThrow<IllegalArgumentException>` 케이스를 추가한다.

#### 10-2. 서비스 테스트

`InvestmentDashboardServiceTest.kt`에 새 데이터 소스 관련 케이스를 추가한다:
- 새 소스가 성공일 때 전체 SUCCESS
- 새 소스가 실패해도 나머지는 SUCCESS
- 세 소스가 모두 성공할 때 새 소스도 포함됨

#### 10-3. 집계 컨트롤러 테스트

`InvestmentDashboardControllerTest.kt`에 추가:
- 정상 응답에 새 섹션이 포함됨
- 새 섹션 FAILURE 시 해당 필드에 `failureReason` 포함

#### 10-4. 도메인 단독 리소스 컨트롤러 테스트

`InvestmentResourceControllerTest.kt`에 추가(기존 패턴 그대로):
- 성공 → `200` + 데이터 + **속성별 `Cache-Control` 헤더**(실시간성 높음 `no-store` / 낮음 `private, max-age={TTL*60}`)
- 실패 → 매핑된 상태(예: CIRCUIT_OPEN→`503`, TIMEOUT→`504`, RESOURCE_EXHAUSTED→`429`)

#### 10-5. 캐시 테스트 (CachingResilientAdapter인 경우)

`CachingResilientAdapterTest.kt`·`TwoTierCacheTest.kt`가 이미 패턴을 검증하므로 별도 추가 불필요.

---

## 생성 완료 후 실행 (워크플로우 ③ 완료)

```bash
JAVA_HOME=~/.jdks/corretto-25/Contents/Home ./gradlew test
```

테스트가 통과하면 생성된 파일 목록과 각 파일의 역할을 요약한다.
실패하면 실패 원인을 분석하고 즉시 수정한다.

---

## 비판적 검토 (워크플로우 ④ — 반드시 수행)

코드 생성 후 **`/self-review`를 실행**해 자가 검토를 수행한다.
이 단계 없이 사람 리뷰로 넘기지 않는다. (`docs/ai/ai-dev-workflow.md` 참고)

빠른 1차 체크리스트:

- [ ] `portName` 값이 `application.yml` 인스턴스 키와 정확히 일치하는가?
- [ ] `domain` 패키지에 Spring/Resilience4j import가 없는가?
- [ ] 병렬 조회에 `supplyAsyncWithMdc()`를 사용했는가? (`CompletableFuture.supplyAsync()` 직접 사용 금지)
- [ ] CachingResilientAdapter라면 캐시 이름을 `CacheKeyVersionGenerator`로 생성했는가?
- [ ] `InvestmentDashboard` Sealed Result와 서비스의 fetch 함수 이름이 일치하는가?
- [ ] `./gradlew check-all`이 통과하는가?

`/self-review`가 위 항목을 포함한 전체 체크리스트를 점검하고, 사람 리뷰어에게 넘길 보고서를 생성한다.
