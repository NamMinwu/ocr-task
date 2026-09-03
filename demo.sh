#!/usr/bin/env bash
#
# 영상 촬영용 실패 시나리오 세팅 스크립트
#
#   ./demo.sh break-key     OCR 이 실패하는 상태로 만든다
#   ./demo.sh break-upload  OCR 은 성공하고 업로드만 실패하는 상태로 만든다
#   ./demo.sh restore       원래대로 되돌린다
#   ./demo.sh status        지금 무엇이 망가져 있는지 보여준다
#
# 원본은 .demo-backup/ 에 보관되며 restore 로 정확히 복원됩니다.

set -euo pipefail
cd "$(dirname "$0")"

BACKUP=.demo-backup
CONFIG=application-local.yml
TARGET=${2:-img_03.jpg}

c_red=$'\033[31m'; c_grn=$'\033[32m'; c_dim=$'\033[2m'; c_bold=$'\033[1m'; c_off=$'\033[0m'
say()  { printf '%s\n' "$*"; }
ok()   { printf '%s✓%s %s\n' "$c_grn" "$c_off" "$*"; }
warn() { printf '%s!%s %s\n' "$c_red" "$c_off" "$*"; }
step() { printf '\n%s%s%s\n' "$c_bold" "$*" "$c_off"; }

# 세팅 직후 곧바로 돌려서 시트까지 반영시킨다.
run_batch() {
  step "실행 — 시트에 반영합니다"
  set +e
  ./gradlew --no-daemon bootRun --args='--retry-failed' 2>&1 \
    | grep -vE "^Download|honour the JVM|^Daemon|Calculating task|Configuration cache|actionable task|^$|^> Task|^\* |^> Run|^> Get|^FAILURE|^Execution failed|finished with non-zero|^BUILD"
  local code=${PIPESTATUS[0]}
  set -e
  say ""
  say "  ${c_dim}종료 코드: ${code}${c_off}"
}

after_run() {
  say ""
  say "${c_dim}이제 스프레드시트를 열어 확인하세요.${c_off}"
  say ""
  say "${c_dim}복구${c_off}"
  say "  ./demo.sh restore"
  say "  ./gradlew bootRun --args='--retry-failed'"
}

need_clean() {
  if [ -d "$BACKUP" ]; then
    warn "이미 세팅된 상태입니다. 먼저 ./demo.sh restore 를 실행하세요."
    exit 1
  fi
}

case "${1:-}" in


# ─────────────────────────────────────────────────────────────
break-key)
  need_clean
  [ -f "$CONFIG" ] || { warn "$CONFIG 이 없습니다"; exit 1; }
  [ -f "output/raw/${TARGET%.*}.json" ] || { warn "output/raw/${TARGET%.*}.json 이 없습니다. 먼저 정상 실행을 한 번 하세요."; exit 1; }
  mkdir -p "$BACKUP"

  cp "$CONFIG" "$BACKUP/$CONFIG"
  cp "output/raw/${TARGET%.*}.json" "$BACKUP/"

  # 이 한 장만 저장된 결과를 지운다 → --retry-failed 에서 이 장만 API 를 부른다.
  rm -f "output/raw/${TARGET%.*}.json"
  # 키를 잘못된 값으로 → 그 호출이 401 로 실패한다.
  /usr/bin/sed -i "" 's|^\( *api-key: *\).*|\1"sk-ant-invalid-key-for-demo"|' "$CONFIG"

  step "API 호출 실패 세팅 완료"
  ok "api-key 를 잘못된 값으로 교체"
  ok "output/raw/${TARGET%.*}.json 제거 — 이 한 장만 API 를 부르도록"
  run_batch
  say ""
  say "  ${c_dim}시트: 목록의 제목이 파일명으로, 분석 칸에 실패 사유, ${c_bold}사진은 정상${c_off}"
  after_run
  ;;

# ─────────────────────────────────────────────────────────────
break-upload)
  need_clean
  [ -f "input/$TARGET" ] || { warn "input/$TARGET 이 없습니다"; exit 1; }
  mkdir -p "$BACKUP"

  cp "input/$TARGET" "$BACKUP/$TARGET"
  [ -f "output/raw/${TARGET%.*}.json" ] && cp "output/raw/${TARGET%.*}.json" "$BACKUP/" || true

  # 파일명에 작은따옴표를 넣으면 Drive 검색 질의가 깨져 400 이 난다.
  # 400 은 계통 실패(401·403·404)가 아니므로 재시도 후 그 장만 격리된다.
  # 정렬 순서상 원래 자리를 지키도록 확장자 앞에 넣는다.
  APOS=\'                                    # 작은따옴표 한 글자
  QUOTED="${TARGET%.*}${APOS}.${TARGET##*.}"  # img_03.jpg → img_03'.jpg
  printf '%s\n' "$QUOTED" > "$BACKUP/.renamed"
  mv "input/$TARGET" "input/$QUOTED"
  rm -f "output/raw/${TARGET%.*}.json"

  step "업로드 실패 세팅 완료"
  ok "input/$TARGET → input/$QUOTED 로 변경 (Drive 질의가 깨짐)"
  ok "output/raw/${TARGET%.*}.json 제거 — OCR 이 실제로 성공하는 장면이 나오도록"

  run_batch
  say ""
  say "  ${c_dim}시트: 제목과 분석은 정상, ${c_bold}사진 칸만 실패 사유${c_off}"
  after_run
  ;;


# ─────────────────────────────────────────────────────────────
restore)
  [ -d "$BACKUP" ] || { ok "되돌릴 것이 없습니다 (이미 정상 상태)"; exit 0; }

  step "복원"
  if [ -f "$BACKUP/.renamed" ]; then
    q=$(cat "$BACKUP/.renamed")
    rm -f "input/$q" "output/raw/${q%.*}.json"
    ok "input/$q 제거"
  fi
  for f in "$BACKUP"/*.jpg; do
    [ -e "$f" ] || continue
    cp "$f" "input/$(basename "$f")" && ok "input/$(basename "$f")"
  done
  # 저장된 OCR 결과는 일부러 되돌리지 않는다.
  # 그래야 --retry-failed 가 그 장을 실제로 다시 인식하는 장면이 나온다.
  for f in "$BACKUP"/*.json; do
    [ -e "$f" ] || continue
    say "  ${c_dim}output/raw/$(basename "$f") 은 복원하지 않음 — 재시도가 실제로 다시 인식하도록${c_off}"
  done
  if [ -f "$BACKUP/$CONFIG" ]; then
    cp "$BACKUP/$CONFIG" "$CONFIG" && ok "$CONFIG"
  fi
  rm -rf "$BACKUP"
  say ""
  ok "원래 상태로 되돌렸습니다."
  ;;


# ─────────────────────────────────────────────────────────────
*)
  say "영상 촬영용 실패 시나리오 세팅"
  say ""
  say "  ${c_bold}./demo.sh break-key${c_off}    [파일명]    OCR 실패 — 전사가 비고 사진은 남음"
  say "  ${c_bold}./demo.sh break-upload${c_off} [파일명]    업로드 실패 — 전사는 남고 사진이 빔"
  say "  ${c_bold}./demo.sh restore${c_off}                원래대로 되돌리기"
  exit 1
  ;;
esac
