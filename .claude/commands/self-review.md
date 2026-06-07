# self-review — 비판적 자가 검토

방금 작성·수정한 변경분을 사람 리뷰에 넘기기 전에, AI가 스스로 비판적으로 검토하는 단계다.
"동작하니까 됐다"가 아니라 "이 프로젝트의 원칙과 안전 기준에 맞는가"를 확인한다.

이 커맨드는 **개발 직후, 사람 리뷰 직전**에 실행한다. (워크플로우 4단계 — `docs/ai/ai-dev-workflow.md`)

---

## 입력

$ARGUMENTS

(비어 있으면 직전 변경분 전체를 대상으로 한다)

---

## 검토 전 준비

1. `CLAUDE.md`를 읽어 절대 금지 사항·규칙을 확인한다.
2. `git diff` 또는 변경된 파일 목록으로 검토 범위를 파악한다.

---

## 비판적 검토 체크리스트

아래 항목을 **하나씩** 점검하고, 위반·의심 사항을 발견하면 **즉시 수정**한다.
수정 후 관련 테스트를 다시 돌려 통과를 확인한다.

### 1. 안전성 — 버전·의존성
- [ ] 새로 추가한 의존성이 모두 **GA(정식 릴리즈)** 인가? (RC·alpha·beta·milestone·SNAPSHOT 금지)
- [ ] 버전을 `build.gradle.kts` 한 곳에서만 관리하는가?

### 2. 아키텍처 — 헥사고날 경계
- [ ] `domain`이 Spring·Resilience4j·어댑터를 import하지 않는가?
- [ ] `application`이 `adapter` 구현체를 직접 참조하지 않는가?
- [ ] 새 포트는 인터페이스로 정의했는가?
- [ ] 새 파일이 패키지 성격에 맞는 위치에 있는가? (config/adapter/application/domain)

### 3. 휴먼 에러 방지 — 구조적 강제
- [ ] 외부 호출 어댑터가 `ResilientAdapter`/`CachingResilientAdapter`를 상속하는가? (CB 누락 방지)
- [ ] 캐시 이름을 `CacheKeyVersionGenerator`로 생성했는가? (수동 버전 금지)
- [ ] 도메인 모델에 `init { require(...) }` 불변식이 있는가?
- [ ] 코루틴(`suspend`/`async`/`launch`)을 쓰지 않았는가? (ADR-002)
- [ ] 병렬 조회에 `supplyAsyncWithMdc()`를 썼는가? (MDC 전파)

### 4. 동시성·고가용성
- [ ] 새 어댑터가 전용 Executor를 주입받는가? (장애 격리)
- [ ] Bulkhead·TimeLimiter·CB 설정이 데이터 소스 특성에 맞는가?

### 5. 테스트
- [ ] 정상·실패·부분 실패 경로가 모두 테스트되는가?
- [ ] 도메인 `require()` 위반 케이스가 테스트되는가?
- [ ] 테스트가 의미 있는 단언을 하는가? (`shouldNotBe null`만 있는 빈 테스트 금지)

### 6. 문서 일관성
- [ ] 변경이 README·CHANGELOG·관련 ADR에 반영됐는가?
- [ ] 새 설계 결정이라면 ADR을 추가했는가?

---

## 검토 후 실행

```bash
JAVA_HOME=~/.jdks/corretto-25/Contents/Home ./gradlew check-all
```

`check-all`(lintKotlin + test)이 통과해야 검토 완료다.

---

## 결과 보고

검토를 마치면 다음을 요약해 보고한다.

1. **발견·수정한 항목**: 무엇을 왜 고쳤는지
2. **의도적으로 남긴 항목**: 한계로 판단해 남긴 것 + 이유 (사람 리뷰어가 판단하도록)
3. **CI 결과**: `check-all` 통과 여부, 테스트 개수

이 보고가 사람 리뷰어의 시작점이 된다. 사람은 "AI가 놓쳤거나 판단이 필요한 것"에만 집중한다.
