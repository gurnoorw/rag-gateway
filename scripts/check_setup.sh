#!/usr/bin/env bash
# rag-gateway: laptop setup check (macOS, 8 GB). Checks only; it installs nothing.
# Run:  bash check_setup.sh
# Every MISSING line prints the command to install it.

ok()   { printf "  \033[32mOK\033[0m       %s\n" "$1"; }
miss() { printf "  \033[31mMISSING\033[0m  %s\n           install: %s\n" "$1" "$2"; }
warn() { printf "  \033[33mCHECK\033[0m    %s\n" "$1"; }

echo "== Machine"
arch=$(uname -m)
[ "$arch" = "arm64" ] && ok "Apple Silicon ($arch)" || warn "Intel Mac ($arch): everything works, Ollama will be slower"
ram=$(($(sysctl -n hw.memsize) / 1073741824))
ok "RAM ${ram} GB (plan assumes 8 GB: Postgres + Redis + app by default)"
free_gb=$(df -g / | awk 'NR==2{print $4}')
[ "$free_gb" -ge 20 ] && ok "Disk free ${free_gb} GB" || warn "Disk free ${free_gb} GB: keep at least 20 GB free"

echo "== Package manager"
command -v brew >/dev/null && ok "Homebrew $(brew --version | head -1 | awk '{print $2}')" \
  || miss "Homebrew" '/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"'

echo "== Java 21"
if /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
  ok "JDK 21 at $(/usr/libexec/java_home -v 21)"
else
  miss "JDK 21" "brew install --cask temurin@21"
fi
command -v java >/dev/null && echo "           java on PATH: $(java -version 2>&1 | head -1)"

echo "== Containers (OrbStack recommended over Docker Desktop on 8 GB)"
if command -v orb >/dev/null; then ok "OrbStack installed (set memory limit to 4 GB: OrbStack > Settings > System)"
elif command -v docker >/dev/null; then warn "Docker found but not OrbStack. OK if Docker Desktop memory is capped at 4 GB; OrbStack is lighter: brew install --cask orbstack"
else miss "OrbStack" "brew install --cask orbstack"; fi
if command -v docker >/dev/null; then
  docker info >/dev/null 2>&1 && ok "docker daemon running" || warn "docker installed but not running: open OrbStack"
  docker compose version >/dev/null 2>&1 && ok "docker compose $(docker compose version --short 2>/dev/null)" || warn "docker compose not found"
fi

echo "== Git and GitHub"
command -v git >/dev/null && ok "git $(git --version | awk '{print $3}')" || miss "git" "xcode-select --install"
git config user.email >/dev/null && ok "git identity: $(git config user.name)" || warn "set: git config --global user.name/user.email"
if command -v gh >/dev/null; then
  gh auth status >/dev/null 2>&1 && ok "gh logged in" || warn "gh installed, run: gh auth login"
else miss "GitHub CLI (to create the public repo)" "brew install gh && gh auth login"; fi

echo "== Local models (Ollama)"
if command -v ollama >/dev/null; then
  ok "ollama installed"
  models=$(ollama list 2>/dev/null)
  echo "$models" | grep -q "nomic-embed-text" && ok "nomic-embed-text (embeddings, ~300 MB)" || miss "nomic-embed-text" "ollama pull nomic-embed-text"
  echo "$models" | grep -q "llama3.2:1b" && ok "llama3.2:1b (fallback model, ~1.3 GB)" || miss "llama3.2:1b" "ollama pull llama3.2:1b"
  echo "           note: quit Ollama when not on t03/t09 to free RAM"
else miss "Ollama" "brew install ollama   (then: ollama serve, in another tab)"; fi

echo "== Load testing"
command -v k6 >/dev/null && ok "k6 $(k6 version 2>/dev/null | awk '{print $2}')" || miss "k6 (needed from t08)" "brew install k6"

echo "== Claude Code and API key"
command -v claude >/dev/null && ok "Claude Code CLI" || warn "Claude Code CLI not found: see docs.claude.com (Claude Code > install)"
grep -qE '^GATEWAY_CLAUDE_API_KEY=.+' .env 2>/dev/null && ok "GATEWAY_CLAUDE_API_KEY is set in .env" \
  || warn "GATEWAY_CLAUDE_API_KEY not set in .env. Create a key in console.anthropic.com with a monthly spend limit; put it in the repo's .env (gitignored), never in code"

echo "== Memory right now"
pressure=$(memory_pressure 2>/dev/null | grep -i "free percentage" | awk '{print $NF}')
[ -n "$pressure" ] && echo "  system free memory: $pressure (below ~20%: close Chrome tabs / other apps before docker compose up)"

echo
echo "Fix every MISSING line, then start t00 in Claude Code."
