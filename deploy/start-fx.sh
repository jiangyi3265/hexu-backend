#!/usr/bin/env bash
set -euo pipefail

app_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$app_dir"
env_file="${HEXU_ENV_FILE:-$app_dir/.env.production}"

if [[ ! -f fx.jar || ! -f "$env_file" ]]; then
  echo '缺少 fx.jar 或生产环境变量文件；请检查 HEXU_ENV_FILE 路径' >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source "$env_file"
set +a

: "${HEXU_MYSQL_PASSWORD:?请设置数据库密码}"
: "${HEXU_TOKEN_SECRET:?请设置生产令牌密钥}"
: "${HEXU_SENSITIVE_KEY:?请设置生产资料加密密钥}"

if (( ${#HEXU_TOKEN_SECRET} < 32 || ${#HEXU_SENSITIVE_KEY} < 32 )); then
  echo '令牌密钥和资料加密密钥均须至少 32 个字符' >&2
  exit 1
fi

java_major() {
  local first
  first="$("$1" -version 2>&1 | sed -n '1p')" || return 1
  printf '%s\n' "$first" | sed -nE 's/.*version "([0-9]+).*/\1/p'
}

java_bin=''
if [[ -n "${HEXU_JAVA17_BIN:-}" ]]; then
  candidates=("$HEXU_JAVA17_BIN")
else
  candidates=(
    "${JAVA17_HOME:-}/bin/java" "${JAVA_HOME:-}/bin/java"
    "$(command -v java || true)"
    /usr/lib/jvm/*/bin/java /usr/java/*/bin/java
    /www/server/java/*/bin/java /www/server/panel/java/*/bin/java
    /opt/*/bin/java
  )
fi

for candidate in "${candidates[@]}"; do
  [[ -x "$candidate" ]] || continue
  major="$(java_major "$candidate" || true)"
  if [[ "$major" == '17' ]]; then
    java_bin="$candidate"
    break
  fi
done

if [[ -z "$java_bin" ]]; then
  echo '未找到 JDK 17；请为本项目安装 JDK 17，并在 .env.production 设置 HEXU_JAVA17_BIN=/绝对路径/bin/java' >&2
  exit 1
fi

umask 077
mkdir -p .hexu-local/uploads
echo "使用 $java_bin 启动 fx.jar，端口 ${HEXU_SERVER_PORT:-8093}，profile druid,hexu-prod"
if [[ "${1:-}" == '--check' ]]; then
  echo '环境变量与 Java 检查通过；数据库连接将在启动时验证'
  exit 0
fi
exec "$java_bin" -Dfile.encoding=UTF-8 -jar "$app_dir/fx.jar" \
  --spring.profiles.active=druid,hexu-prod \
  --server.address=127.0.0.1 \
  --server.port="${HEXU_SERVER_PORT:-8093}"
