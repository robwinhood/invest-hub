# ADR-004: CachingResilientAdapter — 구조적 캐시 보장

## 상태

확정 (2026-06-06)

## 맥락

데이터 소스마다 실시간성 요구가 다르다:
- **해외 주식**: 실거래가 — 캐시 불가
- **내부 계좌**: 높은 실시간성 — 짧은 TTL 또는 미적용
- **추천 상품**: 수십 분 단위 갱신 — 5분 TTL 캐시 적용

`@Cacheable` 어노테이션 방식은 개발자가 기억해서 붙여야 한다. 새 저실시간 어댑터를 추가할 때 캐시를 빠뜨리면 추천 엔진에 불필요한 부하가 생긴다.

## 결정

`CachingResilientAdapter<T>`를 `ResilientAdapter<T>`의 하위 클래스로 도입한다. 이를 상속하면 캐시가 **구조적으로 보장**된다.

```
ResilientAdapter<T>         ← 실시간 데이터 (캐시 없음)
CachingResilientAdapter<T>  ← 저실시간 데이터 (캐시 자동 적용)
```

어댑터가 어느 클래스를 상속하는지 자체가 데이터 속성에 대한 설계 결정이다.

## 이유

1. **휴먼 에러 방지**: `CachingResilientAdapter`를 상속하는 어댑터는 캐시를 추가로 작성할 필요도, 기억할 필요도 없다. 상속 선택이 곧 캐시 적용이다.

2. **캐시 효율**: 캐시 히트 시 CB·Bulkhead·TimeLimiter를 소모하지 않는다. 외부 시스템 부하를 줄인다.

3. **CB OPEN 시 fallback**: CB가 OPEN 상태일 때도 캐시에 stale 데이터가 있으면 사용자에게 데이터를 제공할 수 있다. 추천 상품처럼 비필수 데이터에서 가치가 크다.

## 캐시 설정

```kotlin
// CacheConfig.kt — TTL과 크기 변경은 이 파일만 수정
@Bean("recommendationsCache")
fun recommendationsCache(): Cache =
    CaffeineCache(
        "recommendations",
        Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(500)
            .build(),
    )
```

## 결과

- 저실시간 어댑터는 반드시 `CachingResilientAdapter<T>`를 상속한다.
- 실시간 어댑터는 `ResilientAdapter<T>`를 상속한다 (캐시 없음).
- `@Cacheable` 어노테이션 방식 사용 금지 — 구조적 보장이 아니다.
- 새 캐시 빈 추가 시 `CacheConfig.kt`에만 추가한다.
