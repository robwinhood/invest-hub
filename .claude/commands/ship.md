# ship — 검증 → 커밋 → 푸시 → PR 생성 (워크플로우 ④~⑤ 자동화)

개발이 끝난 현재 작업 브랜치를 **사람 리뷰 직전까지** 자동으로 끌고 간다.
즉 `④ 자가 검토 → check-all → 커밋·푸시 → PR 생성`을 한 번에 수행한다.
사람은 생성된 PR에서 **승인·머지**(⑤의 마지막)만 한다.

전체 흐름 정의: `docs/ai-dev-workflow.md`

---

## 입력 (선택)

$ARGUMENTS

- 비어 있으면: 현재 브랜치의 커밋·미커밋 변경분 전체를 대상으로 하고 PR 제목/본문은 변경 내용에서 자동 작성한다.
- 채워져 있으면: PR 제목·요약·관련 PRD/이슈 힌트로 사용한다.

---

## 실행 전 가드레일 (어기면 중단하고 사람에게 알릴 것)

1. **현재 브랜치가 `main`/`develop`가 아니어야 한다.** 보호 브랜치 위에서 실행 중이면 중단하고, 먼저 피처 브랜치를 따라고 안내한다.
2. **`gh`가 설치·인증돼 있어야 한다.** 아래로 확인:
   ```bash
   command -v gh >/dev/null && gh auth status
   ```
   인증 안 돼 있으면 중단하고 `gh auth login`(1회, 대화형)을 사람에게 요청한다. 토큰을 임의로 만들거나 추측하지 말 것.
3. **`check-all` 실패 시 PR을 만들지 않는다.** 실패 로그를 사람에게 보고하고 멈춘다. 테스트를 통과시키려고 단언을 약화시키거나 테스트를 삭제하지 말 것.

---

## 단계

### ④ 자가 검토 (먼저, 반드시)

`self-review` 커맨드의 체크리스트(`.claude/commands/self-review.md`)를 그대로 수행한다.
위반·의심 항목은 즉시 수정하고, 의도적으로 남긴 한계는 사유와 함께 기록해 둔다(PR 본문에 들어간다).

### 문서 정합성

- 변경이 `CHANGELOG.md`에 반영됐는지 확인하고, 없으면 `[Unreleased]` 아래 적절한 분류(Added/Changed/Fixed)에 항목을 추가한다.
- 새 설계 결정이 있으면 `docs/adr/`에 ADR을 추가했는지 확인한다.

### 검증

```bash
JAVA_HOME=~/.jdks/corretto-25/Contents/Home ./gradlew check-all
```

- 변경이 **마크다운/문서 전용**이라 Kotlin 검증이 무관하면, 그 사실을 한 줄로 명시하고 건너뛸 수 있다(푸시 후 CI가 재검증).
- 그 외에는 반드시 통과를 확인한다. 실패하면 가드레일 3에 따라 중단.

### 커밋 · 푸시

```bash
git status --short                 # 미커밋 변경 확인
git add -A
git commit -m "<타입>: <요약>

<본문 — 무엇을 왜>

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push -u origin "$(git branch --show-current)"
```

- 이미 모든 변경이 커밋돼 있으면 커밋 단계는 건너뛰고 푸시만 한다.

### PR 생성

`.github/pull_request_template.md` 구조에 맞춰 본문을 채운다.
특히 **"AI 자동 단계 완료 확인(①~④)"** 의 ①②③④ 체크와 **"자가 검토 보고서"** 를 ④ 결과로 채운다.

```bash
gh pr create --base main --head "$(git branch --show-current)" \
  --title "<PR 제목>" \
  --body "<템플릿 채운 본문 + 자가 검토 보고서>"
```

생성 후 PR URL을 사람에게 보고한다.

---

## 머지 정책 (중요)

- **머지는 기본적으로 사람이 한다.** `/ship`은 PR 생성까지만 한다.
- 사람이 명시적으로 "머지까지 하라"고 지시한 경우에만 CI 통과를 확인한 뒤 머지한다:
  ```bash
  gh pr checks --watch        # CI 통과 확인
  gh pr merge --squash --delete-branch
  ```
- CI가 실패 중이면 절대 머지하지 않는다.

---

## 결과 보고

1. **자가 검토 보고서**: 발견·수정 항목 / 의도적으로 남긴 한계 + 이유
2. **검증 결과**: `check-all` 통과 여부, 테스트 개수 (또는 문서 전용이라 생략한 사유)
3. **PR 링크**: 생성된 PR URL
4. 머지를 지시받았다면 머지 결과까지.
