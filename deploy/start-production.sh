#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
set -a
# shellcheck disable=SC1091
source "$project_dir/.env.production"
set +a

: "${HEXU_MYSQL_PASSWORD:?请设置数据库密码}"
: "${HEXU_TOKEN_SECRET:?请设置生产令牌密钥}"
: "${HEXU_SENSITIVE_KEY:?请设置生产资料加密密钥}"

cd "$project_dir"
umask 077
mkdir -p .hexu-local/uploads

exec java -jar "$project_dir/ruoyi-admin/target/ruoyi-admin.jar" \
  --spring.profiles.active=druid,hexu-prod
