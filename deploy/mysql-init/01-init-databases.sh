#!/bin/bash
# MySQL 首次初始化：按服务建库，并按依赖顺序应用各服务的建表与增量脚本。
# 由 mysql 官方镜像的 /docker-entrypoint-initdb.d 机制自动执行（仅首次建卷时运行一次）。
# 挂载约定见 docker-compose.yml 中 mysql 服务的 volumes。
set -euo pipefail

# mysql 官方镜像在 initdb 阶段 root 已带 MYSQL_ROOT_PASSWORD，裸 `mysql -uroot` 会
# Access denied；经 MYSQL_PWD 传密码（不走命令行参数，避免 ps 泄漏与告警）。
export MYSQL_PWD="${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"
mysql() { command mysql -uroot "$@"; }

echo "[codewise-init] creating databases..."
mysql <<-EOSQL
CREATE DATABASE IF NOT EXISTS codewise_user      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS codewise_question  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS codewise_review    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS codewise_community DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS codewise_message   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS codewise_ai        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
EOSQL

# 业务账号对全部 codewise 库授权（mysql 官方镜像默认只授予 MYSQL_DATABASE 一个库；
# OutboxPro 启动时需要 CREATE TABLE 建 outboxpro_* 表，DML 更是日常必需）
echo "[codewise-init] granting ${MYSQL_USER} on codewise_* ..."
mysql <<-EOSQL
GRANT ALL PRIVILEGES ON codewise_user.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON codewise_question.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON codewise_review.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON codewise_community.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON codewise_message.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON codewise_ai.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOSQL

apply() {
  local db="$1"; local file="$2"
  if [ -f "$file" ]; then
    echo "[codewise-init] applying $file -> $db"
    mysql "$db" < "$file"
  else
    echo "[codewise-init] SKIP missing $file"
  fi
}

# 1. 用户库（含 root 启动引导所依赖的 user 表）
apply codewise_user /sql/user/user.sql

# 2. 题目库（judge 与 question 共库；基础脚本已含 case_hash 唯一键等全部增量）
apply codewise_question /sql/question/question.sql
apply codewise_question /sql/question/function_question.sql
apply codewise_question /sql/question/function_question_seed.sql

# 3. 复习库（基础脚本已含 idx_review_schedule 等增量；
#    存量老库的增量脚本见各服务 resources，仅供手工升级使用）
apply codewise_review /sql/review/sql.sql

# 4. 社区库（基础脚本已含 comment/tags type 列与 solution 表）
apply codewise_community /sql/community/sql.sql

# 5. 消息库（含 notification_center、consumed_event）
apply codewise_message /sql/message/sql.sql

# 6. AI 库（codewise_ai.sql 已含 ai_message.status；consumed_event 单独建）
apply codewise_ai /sql/ai/codewise_ai.sql
apply codewise_ai /sql/ai/consumed_event.sql

echo "[codewise-init] done."
