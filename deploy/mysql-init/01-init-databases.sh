#!/bin/bash
# MySQL 首次初始化：按服务建库，并按依赖顺序应用各服务的建表与增量脚本。
# 由 mysql 官方镜像的 /docker-entrypoint-initdb.d 机制自动执行（仅首次建卷时运行一次）。
# 挂载约定见 docker-compose.yml 中 mysql 服务的 volumes。
set -euo pipefail

echo "[codewise-init] creating databases..."
mysql -uroot <<-EOSQL
CREATE DATABASE IF NOT EXISTS codewise_user      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS codewise_question  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS codewise_review    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS codewise_community DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS codewise_message   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS codewise_ai        DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
EOSQL

apply() {
  local db="$1"; local file="$2"
  if [ -f "$file" ]; then
    echo "[codewise-init] applying $file -> $db"
    mysql -uroot "$db" < "$file"
  else
    echo "[codewise-init] SKIP missing $file"
  fi
}

# 1. 用户库（含 root 启动引导所依赖的 user 表）
apply codewise_user /sql/user/user.sql

# 2. 题目库（judge 与 question 共库；函数题、事件 Outbox 逐个应用）
apply codewise_question /sql/question/question.sql
apply codewise_question /sql/question/function_question.sql
apply codewise_question /sql/question/function_question_seed.sql
apply codewise_question /sql/question/function_test_case_unique_hash.sql
apply codewise_question /sql/question/event_outbox.sql

# 3. 复习库（完整结构 + 增量索引 + Outbox）
apply codewise_review /sql/review/sql.sql
for f in /sql/migration-review/*.sql; do [ -e "$f" ] && apply codewise_review "$f"; done
apply codewise_review /sql/review/event_outbox.sql

# 4. 社区库（完整结构 + 增量）
apply codewise_community /sql/community/sql.sql
for f in /sql/migration-community/*.sql; do [ -e "$f" ] && apply codewise_community "$f"; done

# 5. 消息库（含 notification_center、consumed_event）
apply codewise_message /sql/message/sql.sql

# 6. AI 库（会话/消息 + ai_message status 迁移 + consumed_event）
apply codewise_ai /sql/ai/codewise_ai.sql
apply codewise_ai /sql/ai/migration_20260823_ai_message_status.sql
apply codewise_ai /sql/ai/consumed_event.sql

echo "[codewise-init] done."
