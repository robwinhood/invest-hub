# ADR-002: Kotlin 코루틴 미사용 — Java 스레드 모델 고수

## 상태

확정 (2026-05-31)

## 맥락

Kotlin 프로젝트에서 비동기·병렬 처리를 구현할 때 자연스러운 선택지는 두 가지다:
- Kotlin Coroutines (`suspend`, `async`, `launch`, `Dispatchers`)
- Java Thread 모델 (`CompletableFuture`, `ExecutorService`, Virtual Thread)

## 결정

**Kotlin 코루틴을 사용하지 않는다.** Java 스레드 모델(`CompletableFuture` + Virtual Thread)을 고수한다.

## 이유

1. **프로젝트 설계 원칙**: 이 프로젝트는 명시적으로 스레드 기반 설계를 채택한다. 코루틴은 다른 패러다임이며 두 방식을 섞으면 코드 일관성이 무너진다.

2. **Virtual Thread와 코루틴의 중복**: Java 25 Virtual Thread는 코루틴이 해결하려는 문제(블로킹 I/O의 스레드 점유)를 JVM 레벨에서 해결한다. 코루틴을 추가로 도입할 이유가 없다.

3. **Resilience4j와의 통합**: Resilience4j의 `TimeLimiter.executeFutureSupplier()`는 `CompletableFuture`를 기반으로 설계됐다. 코루틴과 함께 사용하려면 별도 브릿지 코드가 필요하다.

4. **가독성**: `CompletableFuture.supplyAsync()`는 Java 개발자라면 누구나 아는 표준 API다. 코루틴은 추가 학습이 필요하다.

## 결과

- `build.gradle.kts`에 `kotlinx-coroutines-*` 의존성 추가 금지.
- `suspend`, `async`, `launch`, `withContext`, `runBlocking`, `Dispatchers`, `Flow` 사용 금지.
- 병렬 처리는 반드시 `supplyAsyncWithMdc()` 래퍼를 통한 `CompletableFuture` 패턴을 사용한다.
- 이 결정을 번복하려면 서비스 레이어 전체와 어댑터 레이어를 재작성해야 한다.
