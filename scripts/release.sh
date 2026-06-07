#!/usr/bin/env bash
#
# release.sh — 버전 컷(릴리즈) 자동화의 "결정적" 코어.
#
# 하는 일:
#   1) CHANGELOG.md 의 [Unreleased] 내용을 읽어 SemVer 올릴 자리(major/minor/patch)를 판정
#   2) 직전 버전에서 다음 버전을 계산
#   3) CHANGELOG.md 를 컷  ([Unreleased] 누적분 → [X.Y.Z] — 오늘, 맨 위엔 빈 [Unreleased])
#   4) build.gradle.kts 의 version 을 동기화
#   5) 다음 버전을 .release-version 파일에 기록 (CI/호출자가 읽음)
#
# 커밋·태그·푸시는 하지 않는다(호출자 책임). 파일만 바꾼다 → 로컬·CI 양쪽에서 재사용.
#
# 사용:
#   bash scripts/release.sh            # 변경 성격 보고 자동 판정
#   bash scripts/release.sh patch|minor|major   # 자리 강제
#   bash scripts/release.sh 1.0.0      # 버전 명시
#
# 올릴 변경이 없으면(=[Unreleased] 비어 있음) 아무것도 안 하고 정상 종료(.release-version 미생성).
set -euo pipefail

CHANGELOG="CHANGELOG.md"
GRADLE="build.gradle.kts"
TODAY="$(date +%F)"
ARG="${1:-}"

[ -f "$CHANGELOG" ] || { echo "error: $CHANGELOG 없음 (저장소 루트에서 실행할 것)"; exit 1; }

# 1) [Unreleased] 블록만 추출 ('## [Unreleased]' 다음 '## [' 직전까지)
unreleased="$(awk '
  /^## \[Unreleased\]/ {grab=1; next}
  grab && /^## \[/ {exit}
  grab {print}
' "$CHANGELOG")"

# 올릴 내용이 있나? (항목 줄 '- ' 존재 여부)
if ! grep -qE '^- ' <<<"$unreleased"; then
  echo "올릴 변경이 없습니다 ([Unreleased]가 비어 있음). 종료."
  exit 0
fi

# 특정 섹션('### Name') 아래에 '- ' 항목이 있는지
section_has_items() {
  awk -v sec="### $1" '
    $0==sec {grab=1; next}
    grab && /^### / {grab=0}
    grab && /^- / {found=1}
    END {exit found?0:1}
  ' <<<"$unreleased"
}

# 2) bump 자리 판정 (Keep a Changelog × SemVer)
bump="patch"
section_has_items "Added" && bump="minor"
section_has_items "Removed" && bump="major"
# 호환 깨짐 키워드가 본문에 있으면 major
grep -qiE 'BREAKING|호환[ ]*(깨|불가)|하위[ ]*호환[ ]*(안|불)' <<<"$unreleased" && bump="major"

case "$ARG" in
  patch|minor|major) bump="$ARG" ;;
esac

# 3) 직전 버전 = [Unreleased] 이후 처음 나오는 ## [X.Y.Z]
current="$(grep -oE '^## \[[0-9]+\.[0-9]+\.[0-9]+\]' "$CHANGELOG" | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || true)"
[ -n "$current" ] || { echo "error: CHANGELOG에서 직전 버전(## [X.Y.Z])을 못 찾음"; exit 1; }
IFS='.' read -r MA MI PA <<<"$current"

# 4) 다음 버전 계산
if [[ "$ARG" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  next="$ARG"
else
  case "$bump" in
    # 0.x 단계 예외: 메이저가 0이면 breaking도 MINOR로 흡수 (1.0.0은 명시할 때만)
    major) if [ "$MA" -eq 0 ]; then MI=$((MI+1)); PA=0; else MA=$((MA+1)); MI=0; PA=0; fi ;;
    minor) MI=$((MI+1)); PA=0 ;;
    patch) PA=$((PA+1)) ;;
  esac
  next="${MA}.${MI}.${PA}"
fi

echo "current=$current  bump=$bump  next=$next  date=$TODAY"

# 5) CHANGELOG 컷: 기존 '## [Unreleased]' 자리에 빈 [Unreleased] + 새 버전 헤딩을 끼움
awk -v ver="$next" -v today="$TODAY" '
  /^## \[Unreleased\]/ && !done {
    print "## [Unreleased]";  print ""
    print "### Added";        print ""
    print "### Changed";      print ""
    print "### Fixed";        print ""
    print "---";              print ""
    print "## [" ver "] — " today
    done=1
    next
  }
  {print}
' "$CHANGELOG" > "$CHANGELOG.tmp" && mv "$CHANGELOG.tmp" "$CHANGELOG"

# 6) build.gradle.kts version 동기화 (SNAPSHOT/RC 등 비-GA 접미사 없이 X.Y.Z)
if [ -f "$GRADLE" ]; then
  perl -i -pe "s/^version = \".*\"/version = \"$next\"/" "$GRADLE"
fi

# 7) 호출자(CI 등)가 읽을 다음 버전 기록
echo "$next" > .release-version
echo "OK: $current -> $next (CHANGELOG·$GRADLE 동기화 완료)"
