#!/usr/bin/env bash
# Confidentiality gate: blocks spreadsheets, stray CSVs, and personnummer
# from ever entering the repo. Runs as pre-commit hook (against staged files)
# and in CI (against the full tree). See CLAUDE.md.
set -euo pipefail

MODE="${1:-staged}" # staged | tree
FAIL=0

list_files() {
  if [ "$MODE" = "tree" ]; then
    git ls-files
  else
    git diff --cached --name-only --diff-filter=ACMR
  fi
}

file_content() {
  if [ "$MODE" = "tree" ]; then
    cat -- "$1" 2>/dev/null || true
  else
    git show ":$1" 2>/dev/null || true
  fi
}

while IFS= read -r f; do
  [ -z "$f" ] && continue

  base="$(basename "$f")"

  # Any file named like the real registration exports
  case "$base" in
    Torsdagstr*|*VT26*|*HT26*|'~$'*)
      echo "BLOCKED: filename suggests real member-data export: $f"
      FAIL=1
      continue
      ;;
  esac

  case "$f" in
    *.xlsx|*.xls|*.xlsm)
      echo "BLOCKED: spreadsheet file must never be committed: $f"
      FAIL=1
      continue
      ;;
    *.csv)
      case "$f" in
        test-data/datasets/*) ;; # allowlisted anonymized fixtures
        *)
          echo "BLOCKED: CSV outside test-data/datasets/ allowlist: $f"
          FAIL=1
          continue
          ;;
      esac
      ;;
  esac

  # Content check: Swedish personnummer (YYMMDD-XXXX / YYYYMMDD-XXXX, dash or plus).
  # Skip binary files (icons, images, jars) — grep -I treats null-byte files as no-match,
  # and piping them through $(...) spews null-byte warnings.
  case "$f" in
    *.png|*.ico|*.icns|*.jpg|*.jpeg|*.gif|*.woff|*.woff2|*.ttf|*.jar) continue ;;
  esac
  if file_content "$f" | grep -qIE '(^|[^0-9])[0-9]{6}([0-9]{2})?[-+][0-9]{4}([^0-9]|$)'; then
    echo "BLOCKED: possible personnummer in: $f"
    FAIL=1
  fi
done < <(list_files)

if [ "$FAIL" -ne 0 ]; then
  echo ""
  echo "Confidentiality check FAILED. See CLAUDE.md — no member data ever enters this repo."
  exit 1
fi
echo "Confidentiality check OK."
