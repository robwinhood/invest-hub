# release — 버전 올리기(릴리즈 컷) 자동화

`[Unreleased]`에 쌓인 변경을 **하나의 릴리즈 버전으로 확정(cut)** 하는 전 과정을 자동화한다.
사용자는 버전 규칙(SemVer)을 몰라도 된다. **`/release` 만 치면** 변경 성격을 보고 알아서 버전을 정한다.

> 왜 필요한가: 이 프로젝트의 버전은 **CHANGELOG / `build.gradle.kts` / git 태그** 세 군데에 존재한다.
> 손으로 맞추면 드리프트가 생긴다(과거에 실제로 발생). 이 skill이 셋을 항상 한 번에 동기화한다.

---

## 입력

$ARGUMENTS

해석 규칙:
- **비어 있음** → 변경 성격을 보고 버전을 자동 결정(아래 "버전 결정 규칙").
- `patch` | `minor` | `major` → 결정 규칙을 무시하고 해당 자리만 올린다.
- `X.Y.Z` (예: `1.0.0`) → 그 버전으로 명시 지정.
- `push` 포함 → 커밋·태그 후 원격까지 푸시한다(기본은 로컬까지만).

---

## 버전 결정 규칙 (SemVer · Keep a Changelog 기준)

`CHANGELOG.md`의 `[Unreleased]` 블록 내용으로 **자동 판정**한다. 우선순위: Removed/breaking > Added > Fixed.

1. `### Removed` 에 항목이 있거나, 어떤 항목에 **"호환 깨짐 / breaking / 하위 호환 안 됨"** 류 표현이 있으면 → **MAJOR** (`X+1.0.0`)
2. 아니고 `### Added` 에 항목이 있으면 → **MINOR** (`X.Y+1.0`)
3. 아니고 `### Fixed` / `### Security` 만 있으면 → **PATCH** (`X.Y.Z+1`)

> **0.x 단계 예외**: 현재 메이저가 `0`이면(초기 개발 단계, SemVer 관례) breaking 변경도 MAJOR로 올리지 않고 **MINOR로 흡수**한다. `1.0.0`은 사용자가 명시(`/release 1.0.0` 또는 `/release major`)할 때만 찍는다.

**직전 버전**은 CHANGELOG에서 `[Unreleased]` 다음에 처음 나오는 `## [X.Y.Z]` 헤딩에서 읽는다.

---

## 실행 절차

### 0. 가드레일 (어기면 중단하고 사용자에게 알림)
- `[Unreleased]`의 `Added`/`Changed`/`Fixed`/`Removed`/`Security`가 **전부 비어 있으면 중단** — 올릴 변경이 없음.
- 현재 브랜치가 `main`/`develop` 등 **보호 브랜치면**, 커밋 전에 `release/vX.Y.Z` 피처 브랜치를 자동 생성한다(보호 브랜치에 직접 커밋 금지).
- `push`가 요청됐는데 `gh`/git 원격 인증이 안 돼 있으면 푸시 단계만 건너뛰고 사용자에게 수동 푸시 명령을 안내한다.

### 1~3. 버전 산정 + CHANGELOG 컷 + gradle 동기화 — `scripts/release.sh` 호출
> 버전 판정·CHANGELOG 컷·`build.gradle.kts` 동기화 로직은 **`scripts/release.sh` 한 곳**에 있다(완전 자동 CI도 같은 스크립트를 쓴다 — 단일 출처). 직접 손으로 편집하지 말 것.

```bash
bash scripts/release.sh          # 자동 판정
bash scripts/release.sh "$ARGUMENTS_의_patch|minor|major|X.Y.Z"   # 강제/명시 시
```
- 스크립트가 `current=… bump=… next=…` 와 결정된 다음 버전을 출력한다. 이 결과(예: "Added가 있어 MINOR → `0.4.0`")를 사용자에게 한 줄로 알린다.
- 올릴 변경이 없으면(`[Unreleased]` 비어 있음) 스크립트가 그냥 종료한다 → 이 경우 사용자에게 알리고 중단.
- 다음 버전은 스크립트가 남긴 `.release-version` 파일에서 읽고, 읽은 뒤 그 파일은 삭제한다(`vX.Y.Z`의 `X.Y.Z`).

### 4. 검증
- `export JAVA_HOME=~/.jdks/corretto-25/Contents/Home && ./gradlew check-all` 실행.
- 실패하면 **커밋/태그를 만들지 않고** 중단, 실패 로그를 보고한다.

### 5. 커밋 + 태그
- 커밋 메시지: `chore(release): vX.Y.Z` (변경 파일: `CHANGELOG.md`, `build.gradle.kts`).
- 주석 태그 생성: `git tag -a vX.Y.Z -m "Release vX.Y.Z"`.

### 6. 푸시 (입력에 `push`가 있을 때만)
- `git push && git push origin vX.Y.Z`.
  > 이 저장소 remote는 커스텀 SSH 별칭(`github-robwinhood`)이며 `gh`는 `-R robwinhood/invest-hub`로 저장소를 명시한다.

### 7. 결과 보고
- 확정된 버전, 바뀐 파일, 생성된 태그, (했다면) 푸시 여부를 요약한다.
- 푸시를 안 했으면 사용자가 직접 올릴 수 있게 `git push origin vX.Y.Z` 명령을 안내한다.

---

## 한 줄 요약 (사용자용)

- 그냥 `/release` → 알아서 버전 정하고 CHANGELOG·gradle·태그까지 동기화.
- `/release push` → 위 + 원격 푸시까지.
- `/release 1.0.0` → 그 버전으로 명시 릴리즈.

## 참고 — 완전 자동(CI)도 켜져 있다

`.github/workflows/release.yml`이 **main 머지 시 같은 `scripts/release.sh`를 자동 실행**해 버전 컷·커밋·태그·push까지 무인으로 처리한다. 따라서 이 `/release` skill은 (1) 머지 전에 미리 로컬에서 릴리즈를 끊고 싶을 때, (2) 버전을 강제/명시하고 싶을 때 쓰는 **수동 경로**다. 평소엔 `[Unreleased]`에 항목만 적고 머지하면 CI가 알아서 한다.
