#!/usr/bin/env bash
# rag-gateway: every Mermaid block in the README parses and renders (t08, AC-t08-8).
#
#   bash scripts/check_mermaid.sh                 # checks README.md
#   bash scripts/check_mermaid.sh path/to/file.md # checks another Markdown file (used by scripts/tests)
#
# Each ```mermaid block is extracted and rendered to SVG with the official mermaid-cli image, which runs the
# same parser GitHub's renderer uses. A parse error makes mmdc exit non-zero and this script fail.
# The image tag is pinned (released 2026-09-24, more than two weeks before t08) so a new Mermaid release
# cannot change the result; bump it deliberately. Override with MERMAID_CLI_IMAGE.
#
# Needs Docker. Exit code 0 when every block renders, 1 when one fails or the file has no Mermaid block,
# 2 when Docker or the image is unavailable (the check did not run; never reported as a pass).
set -u
cd "$(dirname "$0")/.." || exit 2

IMAGE=${MERMAID_CLI_IMAGE:-minlag/mermaid-cli:12.0.0}
FILE=${1:-README.md}

[ -f "$FILE" ] || { echo "FAIL  no such file: $FILE"; exit 1; }
command -v docker >/dev/null && docker info >/dev/null 2>&1 \
  || { echo "SKIP  docker is not available: the Mermaid check did not run"; exit 2; }
docker image inspect "$IMAGE" >/dev/null 2>&1 || docker pull -q "$IMAGE" >/dev/null 2>&1 \
  || { echo "SKIP  cannot pull $IMAGE: the Mermaid check did not run"; exit 2; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
chmod 777 "$work"   # the image's user must be able to write the SVG

# One .mmd file per block, numbered in order of appearance; the opening line number goes in the name.
awk -v dir="$work" '
  /^```mermaid[[:space:]]*$/ { n++; out = sprintf("%s/block-%02d-line-%d.mmd", dir, n, NR); inblock = 1; next }
  inblock && /^```[[:space:]]*$/ { inblock = 0; close(out); next }
  inblock { print > out }
  END { if (inblock) { print "unterminated mermaid block" > "/dev/stderr"; exit 3 } }
' "$FILE" || { echo "FAIL  $FILE: unterminated mermaid block"; exit 1; }

shopt -s nullglob
blocks=("$work"/*.mmd)
[ ${#blocks[@]} -gt 0 ] || { echo "FAIL  $FILE has no \`\`\`mermaid block"; exit 1; }

failures=0
for mmd in "${blocks[@]}"; do
  name=$(basename "$mmd" .mmd)
  if docker run --rm -u "$(id -u):$(id -g)" -v "$work:/data" "$IMAGE" \
       -i "/data/$name.mmd" -o "/data/$name.svg" > "$work/$name.log" 2>&1 \
     && [ -s "$work/$name.svg" ]; then
    echo "OK    $FILE $name renders ($(wc -c < "$work/$name.svg") bytes of SVG, $IMAGE)"
  else
    echo "FAIL  $FILE $name does not render:"
    grep -Ev '^Generating|^[[:space:]]*$|^[[:space:]]+at |Parser\.' "$work/$name.log" | sed 's/^/        /' | head -8
    failures=$((failures + 1))
  fi
done

[ "$failures" -eq 0 ] && exit 0 || exit 1
