# ADR-003: ResilientAdapter 추상 클래스 — CB·Bulkhead·TL 템플릿화

## 상태

확정 (2026-06-06)

## 맥락

세 어댑터(`InternalAccountAdapter`, `PartnerForeignStockAdapter`, `RecommendationEngineAdapter`)가 모두 동일한 내결함성 구조를 가진다:

```kotlin
circuitBreaker.executeCallable {
    bulkhead.executeCallable {
        timeLimiter.executeFutureSupplier {
            CompletableFuture.supplyAsync(call, executor)
        }
    }
}
```

이 구조를 각 어댑터에 반복 작성하면:
- 새 어댑터 추가 시 이 패턴을 기억해서 직접 작성해야 한다.
- 데코레이션 순서(CB → BH → TL)가 어딘가에서 잘못 구현될 수 있다.
- 변경 시 세 곳을 동시에 수정해야 한다.

## 결정

`ResilientAdapter<T>` 추상 클래스를 도입한다. 새 어댑터는 `portName`과 비즈니스 로직(`execute {}` 호출)만 구현하면 된다.

## 이유

1. **휴먼 에러 방지**: 데코레이션 순서 실수를 구조적으로 차단한다. 순서는 `ResilientAdapter`가 한 곳에서 보장한다.
2. **확장성**: 새 데이터 소스 추가 시 `portName` 선언 하나로 `application.yml`의 CB/BH/TL 설정이 자동 연결된다.
3. **일관성**: 모든 어댑터가 동일한 내결함성 패턴을 보장받는다.

## 데코레이션 순서 근거

CB → Bulkhead → TimeLimiter 순서를 변경해서는 안 된다:
- CB가 OPEN이면 Bulkhead·TL 자원을 전혀 소모하지 않고 즉시 반환한다.
- Bulkhead가 꽉 차면 TL Future를 생성하지 않는다.
- 역순이면 자원을 소비한 후 CB에서 차단하는 비효율이 생긴다.

## Bulkhead 타입 선택 근거

`BulkheadRegistry.bulkhead()` = Semaphore Bulkhead (기본값)을 사용한다.  
`BulkheadRegistry.threadPoolBulkhead()` 사용 금지.

Virtual Thread는 블로킹 I/O 시 OS 스레드를 점유하지 않는다. Thread Pool Bulkhead는 별도 스레드 풀을 만들어 격리하는데, Virtual Thread 환경에서는 이 이점이 없다. Semaphore로 동시 접근 수만 제어하는 것이 자연스럽다.

## 결과

- 모든 Out 어댑터는 `ResilientAdapter<T>` 또는 `CachingResilientAdapter<T>`를 상속해야 한다.
- 어댑터에서 CB/BH/TL을 직접 주입하여 데코레이션하는 코드 작성 금지.
- `portName`은 `application.yml`의 인스턴스 키와 정확히 일치해야 한다.
