# API Reference

invest-hub가 노출하는 전체 HTTP 엔드포인트 명세다. 설계 배경은 [ADR-008](adr/008-aggregate-plus-resource-endpoints.md)(집계 + 도메인별 리소스), [ADR-006](adr/006-partial-success-sealed-class.md)(Partial Success)을 참고한다.

> 🧪 **서버 없이 브라우저에서 직접 호출해보고 싶다면** → **https://robwinhood.github.io/invest-hub/api/** 인터랙티브 탐색기(Swagger UI + 브라우저 목, 소스: [docs/api/](api/index.html)). "Try it out"을 누르면 백엔드 없이 브라우저 내 목이 Mock 응답을 돌려준다.

## 공통 사항

| 항목 | 값 |
|---|---|
| API 베이스 URL | `http://localhost:8080` |
| 관리(Actuator) URL | `http://localhost:8081` |
| Context Path | `/` (기본값) |

### 요청 헤더

| Header | Required | Description |
|---|---|---|
| `X-User-Id` | 비즈니스 API 필수 | 사용자 식별자. 운영 환경에서는 API Gateway가 JWT 검증 후 주입한다. `/health/*`·`/actuator/*`·`/admin/cache/*`에는 불필요 |

> POC 단계라 모든 외부 시스템은 Mock이다. `X-User-Id`는 임의 값(`user-001` 등)이면 동작하며, 그 값을 기반으로 가짜 데이터가 생성된다.

### 응답 헤더

| Header | Description |
|---|---|
| `X-Request-Id` | 요청 추적 UUID — 서버 로그의 `requestId` 필드와 1:1 대응 (모든 응답에 자동 부여) |
| `Cache-Control` | 엔드포인트의 데이터 속성별로 상이 (아래 각 명세 참고) |

### Rate Limiting

전역 Rate Limiter가 최대 **15,000 TPS**로 제한한다(`/health/*`·`/actuator/*` 제외). 초과 시 `429 Too Many Requests` + `application/problem+json` 응답.

---

## 1. 투자 대시보드 (집계)

### `GET /api/v1/investment/dashboard`

세 데이터 소스(내부 원장·제휴사 해외주식·추천 엔진)를 가상 스레드에서 병렬 조회해 단일 응답으로 조합한다. **첫 화면 1회 렌더용**.

- **Cache-Control**: `no-store`
- **실패 처리**: 한 섹션이 실패해도 200을 반환하고, 해당 섹션만 `status=FAILURE`로 표기한다 (Partial Success).

**Request**

```http
GET /api/v1/investment/dashboard
X-User-Id: user-001
```

**Response — 전체 성공 (200)**

```json
{
  "userId": "user-001",
  "generatedAt": "2026-05-31T12:00:00.000000",
  "totalAssetValueInKrw": 27048200,
  "assetSummary": {
    "status": "SUCCESS",
    "totalBalance": 23751867,
    "savingsList": [
      {
        "accountId": "ACC-user-001-001",
        "accountType": "SAVINGS",
        "accountTypeLabel": "보통예금",
        "productName": "투자허브 보통예금",
        "balance": 5234567,
        "currency": "KRW"
      }
    ],
    "investmentList": [
      {
        "holdingId": "HOLD-user-001-001",
        "holdingType": "FUND",
        "holdingTypeLabel": "펀드",
        "name": "글로벌 혼합형 펀드",
        "currentValue": 5502300,
        "purchaseValue": 5000000,
        "returnRate": 10.05,
        "currency": "KRW"
      }
    ]
  },
  "foreignStockPortfolio": {
    "status": "SUCCESS",
    "totalValueInKrw": 3296333,
    "holdingList": [
      {
        "ticker": "AAPL",
        "stockName": "Apple Inc.",
        "quantity": 5,
        "currentPrice": 189.50,
        "currency": "USD",
        "currentValueInKrw": 1270925,
        "purchaseValueInKrw": 1100000,
        "returnRate": 15.54
      }
    ]
  },
  "recommendedProducts": {
    "status": "SUCCESS",
    "productList": [
      {
        "productId": "PROD-001",
        "productType": "ETF",
        "productTypeLabel": "ETF",
        "name": "미국 S&P500 ETF",
        "description": "미국 500대 기업에 분산 투자하는 대표 ETF.",
        "expectedReturnRate": 8.50,
        "riskLevel": "MEDIUM",
        "riskLevelLabel": "보통",
        "minimumAmount": 10000
      }
    ]
  }
}
```

**Response — 부분 실패 (200, 제휴사 타임아웃 예시)**

```json
{
  "assetSummary": { "status": "SUCCESS", "...": "..." },
  "foreignStockPortfolio": {
    "status": "FAILURE",
    "failureReason": "TIMEOUT"
  },
  "recommendedProducts": { "status": "SUCCESS", "...": "..." }
}
```

- `status`: `SUCCESS` | `FAILURE`
- `failureReason`: `CIRCUIT_OPEN` | `TIMEOUT` | `RESOURCE_EXHAUSTED` | `SERVICE_UNAVAILABLE`

---

## 2. 도메인별 리소스 엔드포인트

집계와 병행한다. 도메인별 단독 조회·갱신을 담당하며, 데이터 속성에 맞는 `Cache-Control`이 적용되고, 한 도메인 장애가 다른 엔드포인트에 영향을 주지 않는다. 집계와 달리 **실패를 HTTP 상태 코드로 표현**한다.

| 엔드포인트 | 도메인 | `Cache-Control` | 비고 |
|---|---|---|---|
| `GET /api/v1/investment/assets` | 내 계좌/자산 | `private, max-age=30` | 내부 원장 |
| `GET /api/v1/investment/foreign-stocks` | 해외 주식 | `no-store` | 제휴사 시스템, 실시간 |
| `GET /api/v1/investment/recommendations` | 추천 상품 | `private, max-age=300` | 추천 엔진, 저실시간 (L2 TTL 5분과 정렬) |

세 엔드포인트 모두 `X-User-Id` 헤더가 필수다. 성공 시 200 + 해당 섹션 DTO(위 대시보드의 각 `*Summary`/`*Portfolio`/`*Products` 구조와 동일), 실패 시 아래 상태 코드를 반환한다.

### 실패 사유 → HTTP 상태 매핑

| `FailureReason` | HTTP Status | 의미 |
|---|---|---|
| `CIRCUIT_OPEN`, `SERVICE_UNAVAILABLE` | `503 Service Unavailable` | 재시도 권장 |
| `TIMEOUT` | `504 Gateway Timeout` | 상류 지연 |
| `RESOURCE_EXHAUSTED` | `429 Too Many Requests` | backoff 후 재시도 |

**예시**

```bash
# 해외 주식만 새로고침 (집계 재호출 없이 — 자원 절약)
curl -i -H "X-User-Id: user-001" http://localhost:8080/api/v1/investment/foreign-stocks
# → 200, 헤더 Cache-Control: no-store

# 추천만 조회 (클라이언트 캐시 5분 — L2 TTL과 정렬)
curl -i -H "X-User-Id: user-001" http://localhost:8080/api/v1/investment/recommendations
# → 200, 헤더 Cache-Control: max-age=300, private

# 내 계좌/자산만 조회
curl -i -H "X-User-Id: user-001" http://localhost:8080/api/v1/investment/assets
# → 200, 헤더 Cache-Control: max-age=30, private
```

---

## 3. Cache Admin API — 운영 도구

TTL 만료를 기다리지 않고 캐시를 즉시 무효화·재갱신한다. ML 모델 교체, 운영 데이터 긴급 수정 시 사용한다. 무효화는 L1·L2를 모두 비우고 Pub/Sub으로 타 Pod L1까지 전파한다. `refresh`는 비운 직후 데이터 소스를 능동 호출해 채우므로 사용자 캐시 미스가 없다.

| Method | 경로 | 동작 | 응답 |
|---|---|---|---|
| `GET` | `/admin/cache` | 등록된 캐시 목록 조회 (버전 해시 포함) | `{ caches: [...], count }` |
| `DELETE` | `/admin/cache/{cacheName}/{key}` | 특정 키 무효화 | `{ status: "evicted", cache, key }` |
| `POST` | `/admin/cache/{cacheName}/{key}/refresh` | 특정 키 무효화 + 즉시 재갱신 | `{ status: "evicted_and_refreshed", cache, key }` |
| `DELETE` | `/admin/cache/{cacheName}` | 전체 무효화 | `{ status: "evicted_all", cache }` |
| `POST` | `/admin/cache/{cacheName}/refresh` | 전체 무효화 + 재갱신 | `{ status: "evicted_all_and_refreshed", cache }` |

**예시**

```bash
# 등록된 캐시 목록 조회 (버전 해시 포함)
curl http://localhost:8080/admin/cache

# 특정 사용자 캐시 무효화
curl -X DELETE http://localhost:8080/admin/cache/recommendations/user-001

# 특정 사용자 캐시 무효화 + 즉시 재갱신 (캐시 미스 없음)
curl -X POST http://localhost:8080/admin/cache/recommendations/user-001/refresh

# 전체 무효화
curl -X DELETE http://localhost:8080/admin/cache/recommendations
```

> 프로덕션에서는 이 엔드포인트를 API Gateway IP 화이트리스트 또는 내부망으로 제한해야 한다.

---

## 4. Health Probe (Kubernetes)

헤더 불필요. Rate Limiter 적용 제외.

| Method | 경로 | 정상 | 비정상 |
|---|---|---|---|
| `GET` | `/health/startup` | `200 "OK"` | `503 "warming up"` (웜업 진행 중) |
| `GET` | `/health/ready` | `200 "OK"` | `503 "not ready"` |
| `GET` | `/health/live` | `200 "OK"` | — |

웜업(`WarmupService`)이 끝나기 전까지 `/health/startup`이 503을 반환하므로, K8s는 준비된 Pod에만 트래픽을 보낸다.

```bash
curl http://localhost:8080/health/startup
curl http://localhost:8080/health/ready
curl http://localhost:8080/health/live
```

---

## 5. Observability (Actuator, 포트 8081)

> **왜 8080이 아니라 8081인가** — 운영/관측 평면을 대고객 API(8080)와 **물리적으로 분리**했다.
> actuator는 CircuitBreaker·Bulkhead 상태와 health 상세 등 **내부 상태를 노출**하므로 공개 포트에 두지 않는다.
> 포트가 분리돼 있으면 Ingress/Security Group에서 **8081을 내부망·모니터링에만** 열 수 있고(앱 시큐리티보다 단순·견고),
> K8s liveness/readiness 프로브도 비즈니스 트래픽과 **경쟁하지 않는다**. 설정: `management.server.port` (`application.yml`).

```bash
# 전체 헬스 (CB 포함)
curl http://localhost:8081/actuator/health

# 서킷 브레이커 상태 실시간 조회
curl http://localhost:8081/actuator/circuitbreakers

# Micrometer 메트릭 (Prometheus 연동 가능)
curl http://localhost:8081/actuator/metrics/resilience4j.circuitbreaker.calls
```
