# ADR-001: Spring MVC + Virtual Threads 선택 (WebFlux 미사용)

## 상태

확정 (2026-05-31)

## 맥락

세 데이터 소스를 병렬 조회하는 I/O 집약적 서비스다. 높은 동시성이 필요하며, 향후 JDBC 연동이 예상된다.

후보는 두 가지였다:
- **Spring MVC** + Java Virtual Threads
- **Spring WebFlux** (Reactive/Non-blocking)

## 결정

**Spring MVC + Virtual Threads**를 선택한다.

## 이유

**WebFlux를 선택하지 않은 이유:**

1. JDBC는 본질적으로 블로킹이다. WebFlux에서 JDBC를 쓰려면 R2DBC로 전환해야 하며, R2DBC는 아직 생태계가 JDBC에 비해 좁다.
2. Reactive 스타일은 학습 곡선이 가파르다. 팀 전체가 `Mono`·`Flux`·`flatMap` 체인에 익숙해지는 비용이 크다.
3. 블로킹 코드(라이브러리, 레거시 연동)가 Reactive 파이프라인 안에 들어오면 성능이 오히려 저하된다.

**Virtual Threads를 선택한 이유:**

1. Java 21부터 안정화(JEP 444), Java 25에서 Structured Concurrency(JEP 499) GA.
2. 블로킹 I/O 호출 시 OS 스레드가 아닌 가상 스레드가 파킹된다. 기존 블로킹 코드를 그대로 유지하면서 높은 동시성을 얻는다.
3. Spring Boot 4.0에서 `spring.threads.virtual.enabled: true` 한 줄로 Tomcat 레벨에서 활성화된다.

## 결과

- 모든 Tomcat 요청은 Virtual Thread에서 처리된다.
- 서비스 내 병렬 조회는 `CompletableFuture.supplyAsync(call, virtualThreadExecutor)` 패턴을 사용한다.
- Reactive 타입(`Mono`, `Flux`, `Flow`) 사용 금지.
- 향후 WebFlux 전환이 필요하다면 전면 재설계가 필요하다 — 이 결정을 번복하는 비용은 크다.
