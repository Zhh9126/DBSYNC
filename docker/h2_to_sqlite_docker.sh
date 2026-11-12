#!/bin/bash
# 容器内 H2 → SQLite 一次性迁移（镜像只带 JRE，无 javac，故使用随包预编译的 H2ToSqlite.class）
# 用法：停服后 docker exec -it <容器> bash /app/DBDataSync/tools/h2_to_sqlite.sh
set -e
APP="${1:-/app/DBDataSync}"
HERE="$(cd "$(dirname "$0")" && pwd)"
LIB="$APP/lib"

if [ ! -f "$HERE/H2ToSqlite.class" ]; then
    echo "ERROR: 缺少预编译 H2ToSqlite.class" >&2
    exit 1
fi
if [ ! -f "$APP/data/DBDataSync.mv.db" ]; then
    echo "未发现 H2 元库 $APP/data/DBDataSync.mv.db，无需迁移（全新 SQLite 部署）"
    exit 0
fi
echo "请先确认服务已停止； migrating $APP/data/DBDataSync.mv.db -> DBDataSync.db"
exec "${JAVA_HOME:-/opt/jre8}/bin/java" -cp "$HERE:$LIB/*" H2ToSqlite \
    "$APP/data/DBDataSync" "$APP/data/DBDataSync.db"
