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

### 0. 이중 캐시 (L1 Caffeine + L2 Mock Redis) — `TwoTierCache`

추천 상품은 **L1(로컬 Caffeine) + L2(분산 Redis)** 2계층으로 캐싱한다. 데이터 속성(저실시간·전 Pod 공유 가치)에 맞춘 구조다.

- **L1 (Caffeine)**: Pod-로컬, 최속. 대부분 요청 흡수.
- **L2 (Redis)**: 전 Pod 공유, **직렬화 저장**. 신규 Pod·L1 만료 시에도 원격(추천 엔진) 호출을 줄인다.
- 조회: L1 hit → 반환 / L1 miss·L2 hit → L1 승격 후 반환 / 둘 다 miss → 원본 호출 + L1·L2 write-through.

`TwoTierCache`가 `org.springframework.cache.Cache`를 구현하므로 `CachingResilientAdapter`·무효화 서비스는 변경 없이 동작한다.
Redis는 외부 의존성 없는 **인메모리 Mock(`MockRedisStore`)** 으로 구현했다 — "외부 시스템은 모두 Mock" 제약 + GA 전용 정책(embedded-redis 등 비-GA 회피)에 부합. 실 환경에선 `DistributedCacheStore` 포트를 Lettuce 구현으로 교체하면 상위 코드는 불변이다.

L2가 직렬화 저장을 하므로, 아래 **캐시 키 자동 버전**(#1)이 비로소 실효를 갖는다(직렬화 포맷 충돌 방지).

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
  ├─ TwoTierCache.evict          → L1(Caffeine) + L2(Mock Redis) 동시 제거
  ├─ CacheEventPublisher (port)  → MockRedisCacheEventPublisher (Pub/Sub 발행) → L1EvictionSubscriber → 타 Pod L1 evict
  └─ CacheRefreshStrategy (port) → 즉시 재갱신 전략
```

- 무효화는 **L1·L2를 모두** 비운다. L2는 공유 저장소라 1회 evict로 전 Pod에 반영되고, L1은 Pub/Sub으로 타 Pod에 전파한다.
- `CacheEventPublisher`: 현재 `MockRedisCacheEventPublisher`(빈 이름 `redisCacheEventPublisher`)가 활성. `NoOpCacheEventPublisher`는 `@ConditionalOnMissingBean`으로 자동 비활성화된다. 실 Redis 전환 시 이 발행자만 교체하면 코어 코드는 불변.
- `CacheRefreshStrategy`: 무효화 직후 데이터 소스를 능동 호출해 캐시를 미리 채운다. `supports(cacheName)`로 대상 캐시를 선언한다.

### 3. 운영 API — `CacheAdminController`

`/admin/cache/**`로 무효화·재갱신을 REST로 노출한다.

## 이유

- **휴먼 에러 제거**: 키 버전을 코드 구조에서 자동 도출하므로 사람이 올릴 필요가 없다.
- **범용성**: `DistributedCacheStore`·`CacheEventPublisher` 포트로 분리해, 실 Redis 도입 시 구현체(Mock→Lettuce)만 교체하면 된다.
- **무중단 운영**: 재갱신으로 무효화 직후의 캐시 미스 지연까지 제거한다.
- **자원 효율**: L2 공유 캐시로 신규 Pod·L1 만료 시 원격 추천 엔진 호출을 줄인다.

## 결과

- 캐시 이름은 반드시 `CacheKeyVersionGenerator.versionedName()`으로 생성한다. 문자열 직접 사용 금지.
- 무효화·재갱신은 `CacheInvalidationService`를 통한다. 어댑터에서 직접 `cache.evict()` 호출 금지.
- 분산 무효화는 `MockRedisCacheEventPublisher` → `L1EvictionSubscriber` 경로로 동작한다. 실 Redis 전환 시 발행자/`DistributedCacheStore` 구현만 교체 — 코어 변경 불필요.
- 새 캐시에 즉시 재갱신이 필요하면 `CacheRefreshStrategy`를 구현한다.
