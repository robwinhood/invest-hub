# ADR-007: 캐시 키 자동 버전 관리 및 무효화·재갱신

## 상태

확정 (2026-06-02)

## 맥락

캐시 운영에서 반복적으로 사람 손이 가고 휴먼 에러가 발생하는 지점이 세 가지 있다.

1. **데이터 클래스 변경 시 키 충돌**
   Redis 같은 영속 캐시에서 직렬화한 클래스에 필드가 추가/삭제되면, 구 포맷 JSON과 새 클래스가 충돌해 역직렬화 오류가 발생한다. 통상 개발자가 `cache:v2`처럼 수동으로 버전을 올리는데, 이를 빠뜨리면 운영 장애로 이어진다.

2. **TTL 만료 전 강제 무효화 수단 부재**
   ML 모델 교체나 데이터 긴급 수정 시, TTL(5분)을 기다리지 않고 즉시 캐시를 비울 방법이 없으면 잘못된 데이터가 계속 노출된다.

3. **무효화 후 첫 요청의 캐시 미스 지연**
   단순 무효화는 다음 요청이 들어올 때까지 비어 있어, 그 요청이 캐시 미스로 느려진다.

## 결정

### 1. 캐시 키 자동 버전 — `CacheKeyVersionGenerator`

클래스의 프로퍼티(이름+타입)를 SHA-256으로 해시해 캐시 이름에 자동 삽입한다.

```kotlin
CacheKeyVersionGenerator.versionedName("recommendations", InvestmentProduct::class)
// → "recommendations:v7a0fe702"
```

`InvestmentProduct`에 필드가 추가되면 해시가 자동으로 바뀌어 새 키를 쓴다. 개발자가 버전을 관리할 필요가 없다. 중첩 객체·제네릭·순환 참조도 재귀적으로 해시에 반영한다.

### 2. 무효화·재갱신 — 포트 기반 범용 설계

```
CacheInvalidationService (application/service)
  ├─ 로컬 Caffeine 캐시 즉시 evict
  ├─ CacheEventPublisher (port)        ← 분산 무효화 확장점
  └─ CacheRefreshStrategy (port)       ← 즉시 재갱신 전략
```

- `CacheEventPublisher`: 현재 `NoOpCacheEventPublisher`(로그만). Redis 추가 시 `RedisCacheEventPublisher` 하나만 구현하면 `@ConditionalOnMissingBean`으로 자동 전환되고 코어 코드는 불변.
- `CacheRefreshStrategy`: 무효화 직후 데이터 소스를 능동 호출해 캐시를 미리 채운다. `supports(cacheName)`로 대상 캐시를 선언한다.

### 3. 운영 API — `CacheAdminController`

`/admin/cache/**`로 무효화·재갱신을 REST로 노출한다.

## 이유

- **휴먼 에러 제거**: 키 버전을 코드 구조에서 자동 도출하므로 사람이 올릴 필요가 없다.
- **범용성**: 포트 인터페이스로 분리해 Redis 도입 시 구현체만 교체한다. 지금은 Caffeine만으로도 동작한다.
- **무중단 운영**: 재갱신으로 무효화 직후의 캐시 미스 지연까지 제거한다.

## 결과

- 캐시 이름은 반드시 `CacheKeyVersionGenerator.versionedName()`으로 생성한다. 문자열 직접 사용 금지.
- 무효화·재갱신은 `CacheInvalidationService`를 통한다. 어댑터에서 직접 `cache.evict()` 호출 금지.
- 분산 무효화가 필요해지면 `RedisCacheEventPublisher`를 추가한다 — 코어 변경 불필요.
- 새 캐시에 즉시 재갱신이 필요하면 `CacheRefreshStrategy`를 구현한다.
