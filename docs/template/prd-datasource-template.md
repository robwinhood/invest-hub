# 새 데이터 소스 추가하기 — PRD 작성 가이드

> 이 가이드를 따라하면 코드를 직접 작성하지 않아도 새 기능이 추가됩니다.

> **이 양식은 "필수"가 아니라 "권장"입니다.** 다 채워 주면 AI가 되묻지 않고 한 번에 생성합니다.
> 자연어로 요구사항만 줘도 됩니다 — `/ship`·`/add-datasource`의 **① 요구사항 분석**이 빠진 정보(실시간성·필드·Mock 등)를 질문해 채웁니다.
> 즉 *"정확·일관·무질문"을 원하면 양식을, 빠르게 시작하려면 자연어를* 쓰세요.

---

## 전체 흐름 (3단계)

```
1단계: 아래 PRD 양식을 채운다
          ↓
2단계: Claude Code 채팅창에 입력한다
          ↓
3단계: AI가 코드를 모두 만들고 테스트까지 돌린다
```

---

## 2단계 상세 — Claude Code에 어떻게 입력하나요?

Claude Code 채팅창에 아래처럼 입력합니다.

**슬래시 명령어 + PRD 내용을 한 번에 붙여넣으면 됩니다.**

```
/add-datasource

## 데이터 소스 이름
국내 ETF 포트폴리오

## 실시간성
낮음 (10분 단위)
캐시 TTL: 10분

... (아래 양식 전체를 채워서 붙여넣기)
```

> **팁**: `/add-datasource` 뒤에 엔터를 치고 PRD를 붙여넣어도 됩니다.

---

## PRD 양식 (복사해서 채우세요)

```
## 데이터 소스 이름
(예: 국내 ETF 포트폴리오)

## 섹션 필드명
(JSON 응답에서 이 섹션을 부르는 이름 — 영어 camelCase)
(예: domesticEtfPortfolio)

## 포트 이름
(내부 설정 파일에서 쓰는 이름 — 영어 소문자, 하이픈 연결)
(예: domestic-etf)

## 실시간성
실시간성 높음  ← 주가처럼 매 순간 바뀌는 데이터
실시간성 낮음  ← 추천 상품처럼 수십 분에 한 번 바뀌는 데이터

(낮음을 선택한 경우) 캐시 TTL: ___분

## Resilience4j 설정
CB 실패율 임계: ___%   (기본값 50. 외부 파트너면 40, 비필수면 60 권장)
Bulkhead 최대 동시: ___개  (기본값 80. 외부 파트너면 50 권장)
Timeout: ___초           (기본값 2)

## 포트폴리오 집계 모델
(이 데이터 전체를 대표하는 클래스의 필드 목록)
- 필드이름: 타입 (검증조건)

예시:
- totalValueInKrw: BigDecimal (>= 0)
- holdingList: List

## 보유 항목 모델
(개별 종목 하나의 필드 목록)
- 필드이름: 타입 (검증조건)

예시:
- etfName: String (비어있으면 안 됨)
- ticker: String (비어있으면 안 됨)
- quantity: BigDecimal (> 0)
- currentPrice: BigDecimal (>= 0)
- currentValueInKrw: BigDecimal (>= 0)
- returnRate: BigDecimal

## Mock 데이터 (2~3개)
(실제처럼 그럴듯한 값으로 작성)

항목 1:
  etfName: KODEX 200
  ticker: 069500
  quantity: 50
  currentPrice: 35420
  currentValueInKrw: 1771000
  returnRate: 8.30

항목 2:
  etfName: TIGER 미국S&P500
  ticker: 360750
  quantity: 30
  currentPrice: 18650
  currentValueInKrw: 559500
  returnRate: 12.50
```

---

## 작성 예시 (완성본)

아래를 그대로 복사해서 Claude Code에 `/add-datasource` 뒤에 붙여넣으면 국내 ETF 기능이 추가됩니다.

```
/add-datasource

## 데이터 소스 이름
국내 ETF 포트폴리오

## 섹션 필드명
domesticEtfPortfolio

## 포트 이름
domestic-etf

## 실시간성
낮음 (10분 단위)
캐시 TTL: 10분

## Resilience4j 설정
CB 실패율 임계: 55%
Bulkhead 최대 동시: 70개
Timeout: 2초

## 포트폴리오 집계 모델
- totalValueInKrw: BigDecimal (>= 0)
- holdingList: List

## 보유 항목 모델
- etfName: String (비어있으면 안 됨)
- ticker: String (비어있으면 안 됨)
- quantity: BigDecimal (> 0)
- currentPrice: BigDecimal (>= 0)
- currentValueInKrw: BigDecimal (>= 0)
- returnRate: BigDecimal

## Mock 데이터
항목 1:
  etfName: KODEX 200
  ticker: 069500
  quantity: 50
  currentPrice: 35420
  currentValueInKrw: 1771000
  returnRate: 8.30

항목 2:
  etfName: TIGER 미국S&P500
  ticker: 360750
  quantity: 30
  currentPrice: 18650
  currentValueInKrw: 559500
  returnRate: 12.50
```

---

## 자주 묻는 질문

**Q. 섹션 필드명과 포트 이름을 뭐로 해야 하나요?**

- 섹션 필드명: JSON 응답에서 이 데이터를 부르는 이름입니다. 영어 camelCase로 씁니다.
  - 예: `domesticEtfPortfolio`, `foreignBondPortfolio`, `cryptoHoldings`
- 포트 이름: 내부 설정에서 쓰는 이름입니다. 영어 소문자 + 하이픈으로 씁니다.
  - 예: `domestic-etf`, `foreign-bond`, `crypto`

**Q. 실시간성을 어떻게 판단하나요?**

"5분 전 데이터를 보여줘도 괜찮은가?"로 판단하세요.
- 주가, 환율 → 괜찮지 않다 → **실시간성 높음**
- 추천 상품, ETF 포트폴리오 → 괜찮다 → **실시간성 낮음**

**Q. CB 실패율을 뭘로 설정해야 하나요?**

| 데이터 소스 유형 | 권장 설정 |
|---|---|
| 외부 파트너/제휴사 API | 40% (빠르게 차단) |
| 내부 시스템 | 50% (기본값) |
| 참고용 비필수 데이터 | 60% (관대하게) |

**Q. AI가 코드를 만든 뒤 제가 해야 할 일이 있나요?**

AI가 테스트까지 자동으로 돌립니다. 테스트가 통과하면 PR을 만들고 `.github/pull_request_template.md`의 체크리스트를 확인하면 됩니다.
