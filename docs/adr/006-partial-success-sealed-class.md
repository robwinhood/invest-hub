# ADR-006: Partial Success 패턴 — Kotlin Sealed Class

## 상태

확정 (2026-06-06)

## 맥락

세 데이터 소스 중 하나가 실패했을 때 API 전체를 실패로 처리하면:
- 제휴사(해외 주식) 장애 시 내부 계좌 정보도 볼 수 없다.
- 추천 엔진 장애 시 핵심 자산 정보가 차단된다.
- 모바일 앱에서 빈 화면이 노출된다.

이는 사용자 경험 관점에서 비합리적이다.

## 결정

각 데이터 소스의 결과를 **독립적인 Sealed Class**로 표현하고, 서비스는 각 조회를 `runCatching`으로 격리한다.

```kotlin
sealed class ForeignStockPortfolioResult {
    data class Success(val data: ForeignStockPortfolio) : ForeignStockPortfolioResult()
    data class Failure(val reason: FailureReason)       : ForeignStockPortfolioResult()
}
```

## 이유

1. **독립적 실패 격리**: 하나의 `CompletableFuture`가 예외를 던져도 다른 두 Future는 계속 실행된다. `runCatching`이 예외를 `Failure`로 변환한다.

2. **타입 안전성**: `when` 표현식으로 Success/Failure를 처리하면 컴파일러가 모든 케이스를 강제한다. 새 Result 타입 추가 시 처리하지 않은 분기가 있으면 컴파일 에러.

3. **모바일 앱 친화적**: 각 섹션의 `status` 필드로 실패 여부를 판별하고, 실패 섹션에만 에러 UI를 렌더링한다. 성공한 섹션은 정상 노출.

4. **`SectionStatus` enum**: 응답 DTO의 status 값을 `SectionStatus.SUCCESS` / `SectionStatus.FAILURE` enum으로 관리한다. 문자열 하드코딩("SUCCESS")의 오타를 컴파일 타임에 차단한다.

## FailureReason 분류 기준

```kotlin
private fun Throwable.toFailureReason(): FailureReason = when (this) {
    is CallNotPermittedException -> FailureReason.CIRCUIT_OPEN       // CB 차단
    is BulkheadFullException     -> FailureReason.RESOURCE_EXHAUSTED // 동시 요청 초과
    is TimeoutException          -> FailureReason.TIMEOUT            // TL 초과
    else                         -> FailureReason.SERVICE_UNAVAILABLE // 그 외 예외
}
```

새 예외 타입이 생기면 이 `when` 분기에만 추가한다. 응답 DTO는 수정 불필요.

## 결과

- `InvestmentDashboard`는 항상 세 Result를 모두 포함한다. null이 없다.
- 응답 HTTP 상태는 항상 200이다 — 데이터 소스 실패는 비즈니스 실패지 HTTP 실패가 아니다.
- 모든 섹션이 Failure여도 예외를 던지지 않는다 (`shouldNotThrow` 테스트로 강제).
- 새 데이터 소스 추가 시 새 Sealed Class를 정의하고 `InvestmentDashboard`에 필드를 추가한다.
