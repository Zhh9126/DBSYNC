#!/bin/bash
# 一次性把现网 H2 元库全量搬迁到 SQLite。必须在平台停服后运行。
# 用法: bash tools/h2_to_sqlite.sh [安装目录，默认 /datasync/app/DBDataSync-2.1.4]
set -e
APP="${1:-/datasync/app/DBDataSync-2.1.4}"
HERE="$(cd "$(dirname "$0")" && pwd)"
LIB="$APP/lib"
BUILD=/tmp/dbs_h2_to_sqlite_build
mkdir -p "$BUILD"
javac -encoding UTF-8 -cp "$LIB/*" -d "$BUILD" "$HERE/H2ToSqlite.java"
java -cp "$BUILD:$LIB/*" H2ToSqlite "$APP/data/DBDataSync" "$APP/data/DBDataSync.db"
