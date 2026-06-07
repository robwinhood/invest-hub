# ship — PRD에서 PR까지 (전체 개발 파이프라인 자동화)

요구사항(PRD)만 주면 **① 요구사항 분석 → ② 설계 → ③ 개발+테스트 → ④ 자가 검토 → 커밋·푸시 → ⑤ PR 생성** 까지 한 번에 수행한다.
사람이 하는 일은 **PRD 제공**과 최종 **리뷰·승인·머지**뿐이다. (PR 생성까지가 AI, 그 PR을 보고 승인·머지하는 ⑤의 본질은 사람)

**적응형**: 이미 끝난 단계는 건너뛴다. 코드가 이미 작성돼 있으면 ③을 생략하고 ④(자가 검토)부터 — 즉 "마무리만" 모드로 동작한다.

전체 워크플로우 정의: `docs/ai-dev-workflow.md`

---

## 입력 (선택)

$ARGUMENTS

- **PRD/요구사항/이슈가 주어지면** → ① 요구사항 분석부터 전체 파이프라인을 돈다.
- **비어 있고 미커밋 변경이 있으면** → 개발이 끝난 것으로 보고 ④ 자가 검토부터(마무리 모드).
- **비어 있고 작업 트리가 깨끗하면** → 무엇을 ship할지 먼저 묻는다(임의로 추측하지 말 것).

---

## 실행 전 가드레일 (어기면 중단하고 사람에게 알릴 것)

1. **보호 브랜치 보호**: `main`/`develop`에 **직접 커밋 금지**. 현재 보호 브랜치 위라면, 커밋 전에 주제에서 유추한 이름으로 피처 브랜치를 생성한다(`feature/<주제-slug>`). 이름을 합리적으로 정할 수 없으면 사람에게 확인한다.
2. **`gh` 설치·인증 확인**:
   ```bash
   command -v gh >/dev/null && gh auth status
   ```
   안 돼 있으면 중단하고 `gh auth login`(1회, 대화형)을 사람에게 요청한다. 토큰을 임의로 만들거나 추측하지 말 것.
3. **`check-all` 실패 시 PR을 만들지 않는다.** 실패 로그를 보고하고 멈춘다. 통과시키려고 단언을 약화시키거나 테스트를 삭제하지 말 것.
4. **`CLAUDE.md` 절대 금지 사항 준수**: 비-GA 의존성·코루틴·`@WebMvcTest` 등 금지. 설계는 `docs/adr/` 결정을 따른다.

---

## 단계 (적응형 — 남은 단계만 수행)

### ① 요구사항 분석

주어진 PRD(양식 또는 자연어)를 읽고 **범위 · 성공 조건 · 실패 시 동작**을 정리한다.
- 모호하면 `AskUserQuestion`으로 2~3개 핵심만 좁힌다(과한 질문 금지).
- PRD 없이 코드만 있으면(마무리 모드) 변경분에서 의도를 역추출해 한 줄로 요약한다.

### ② 설계

`CLAUDE.md`(금지 사항·스택·패키지 규칙)와 `docs/adr/`(기존 결정)에 맞춰 구현 방향을 정한다.
- 실시간 vs 저실시간 → `ResilientAdapter` vs `CachingResilientAdapter` 등.
- **새로운 설계 결정이 생기면 `docs/adr/`에 ADR을 추가**한다(번호는 다음 순번).

### ③ 개발 + 테스트

- **새 데이터 소스** → `/add-datasource` skill을 활용한다.
- **그 외** → 헥사고날 순서(domain → port → service → adapter)로 구현하고, 각 레이어 테스트(정상·실패·부분 실패)를 동반한다. **테스트 없는 코드는 미완성으로 본다.**
- 이미 구현이 끝나 있으면 이 단계는 건너뛴다.

### ④ 자가 검토 (반드시)

`self-review` 커맨드의 체크리스트(`.claude/commands/self-review.md`)를 그대로 수행한다.
위반·의심 항목은 즉시 수정하고, 의도적으로 남긴 한계는 사유와 함께 기록한다(PR 본문에 들어간다).
(③에서 `/add-datasource`를 사용해 이미 `self-review`를 거쳤다면 중복 실행하지 않는다.)

### 문서 정합성

- `CHANGELOG.md` `[Unreleased]`에 항목 추가(Added/Changed/Fixed).
- 새 설계 결정이면 `docs/adr/` ADR 확인. 영향받는 `README.md`·`CLAUDE.md`도 갱신.

### 검증

```bash
JAVA_HOME=~/.jdks/corretto-25/Contents/Home ./gradlew check-all
```
- **마크다운/문서 전용** 변경이라 Kotlin 검증이 무관하면 그 사실을 한 줄로 명시하고 건너뛸 수 있다(푸시 후 CI 재검증).
- 그 외에는 통과를 확인한다. 실패하면 가드레일 3에 따라 중단.

### 커밋 · 푸시

```bash
git status --short
git add -A
git commit -m "<타입>: <요약>

<본문 — 무엇을 왜>

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push -u origin "$(git branch --show-current)"
```

### ⑤ PR 생성

`.github/pull_request_template.md` 구조에 맞춰 본문을 채운다. **"AI 자동 단계 완료 확인(①~④)"** 체크와 **"자가 검토 보고서"**(④ 결과)를 채운다.
**Assignee는 `--assignee "@me"`로 현재 로그인된 계정을 자동 지정**한다(별도 지정이 없으면 항상 본인).

```bash
gh pr create --base main --head "$(git branch --show-current)" \
  --assignee "@me" \
  --title "<PR 제목>" \
  --body "<템플릿 채운 본문>"
```

생성 후 PR URL을 보고한다.

---

## 머지 정책 (중요)

- **머지는 기본적으로 사람이 한다.** `/ship`은 PR 생성까지만 한다. 이것이 ⑤의 본질(리뷰·승인)이 사람에게 남는 이유다.
- 사람이 명시적으로 "머지까지 하라"고 지시한 경우에만 CI 통과 확인 후 머지한다:
  ```bash
  gh pr checks --watch
  gh pr merge --squash --delete-branch
  ```
- CI가 실패 중이면 절대 머지하지 않는다.

---

## 결과 보고

1. **수행한 단계**: 이번 실행에서 ①~⑤ 중 실제로 수행한 단계(건너뛴 단계와 이유).
2. **자가 검토 보고서**: 발견·수정 항목 / 의도적으로 남긴 한계 + 이유.
3. **검증 결과**: `check-all` 통과 여부, 테스트 개수(또는 문서 전용 사유).
4. **PR 링크**: 생성된 PR URL. 머지를 지시받았다면 머지 결과까지.
