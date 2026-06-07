## 변경 요약

> 무엇을 왜 바꿨는지 한 두 문장으로.

---

## AI 자동 단계 완료 확인 (워크플로우 ①~④)

> 사람 리뷰(⑤) 전에 AI 자동 단계가 끝났는지 확인한다. 자세한 흐름: `docs/ai-dev-workflow.md`

- [ ] ① PRD 정의됨 (양식 또는 자연어 요구사항)
- [ ] ② 설계 방향 결정 (`CLAUDE.md`·`docs/adr/` 준수, 필요 시 새 ADR 작성)
- [ ] ③ 개발 + 테스트 작성 완료
- [ ] ④ `/self-review` 실행 완료 → 아래 "자가 검토 보고서" 첨부
- [ ] `./gradlew check-all` 통과 (CI에서 자동 재검증)

**자가 검토 보고서** (④ 결과 붙여넣기):
- 수정한 항목:
- 의도적으로 남긴 한계 + 이유:

---

## 변경 유형

- [ ] 새 데이터 소스 추가 (`/add-datasource` skill 사용)
- [ ] 기존 도메인 모델 수정
- [ ] 버그 수정
- [ ] 설정 변경 (application.yml, Resilience4j 등)
- [ ] 문서 업데이트
- [ ] 기타

---

## 리뷰어 체크리스트

> PR을 검토하는 사람이 확인할 항목. 모두 체크되어야 Merge 가능.

### 아키텍처
- [ ] `domain` 패키지에 Spring/외부 라이브러리 import가 없다
- [ ] `application` 서비스가 `adapter` 구현체를 직접 참조하지 않는다
- [ ] 새 Out-Port 어댑터가 `ResilientAdapter` 또는 `CachingResilientAdapter`를 상속했다

### 동시성
- [ ] 병렬 조회에 `supplyAsyncWithMdc()`를 사용했다 (`CompletableFuture.supplyAsync()` 직접 사용 금지)
- [ ] 코루틴(`suspend`, `async`, `launch`)을 사용하지 않았다

### 도메인 검증
- [ ] 새 도메인 모델에 `init { require(...) }` 검증이 있다
- [ ] 음수/빈값 등 비정상 입력에 대한 테스트가 있다

### Resilience4j
- [ ] 새 어댑터의 `portName`이 `application.yml` 인스턴스 키와 정확히 일치한다
- [ ] CB/Bulkhead/TL 설정이 데이터 소스 특성에 맞게 조정됐다 (외부일수록 엄격)

### 캐시
- [ ] 저실시간 어댑터는 `CachingResilientAdapter`를 상속했다
- [ ] 실시간 어댑터는 `ResilientAdapter`를 상속했다 (캐시 없음)

### 테스트
- [ ] CI (`./gradlew test`) 가 통과한다
- [ ] 부분 실패 시나리오 테스트가 있다 (하나 실패 시 나머지는 SUCCESS)

### 문서
- [ ] 변경된 설계 결정은 `docs/adr/`에 기록됐다
- [ ] `CHANGELOG.md`에 항목이 추가됐다

---

## 테스트 결과

```
# CI 결과를 붙여넣거나 로컬 실행 결과 요약
./gradlew test → X tests, 0 failures
```

---

## 관련 이슈 / PRD

> 이 PR이 구현하는 PRD 또는 이슈 번호
