#!/bin/bash
# DBDataSync 容器启动脚本（前台 exec，Java 为 PID 1，日志进 stdout，可接收 SIGTERM）
# 与通用 startup.sh 的差异：不使用 nohup 后台（否则 PID 1 退出、容器立即退出）；
# 堆大小可由环境变量控制；conf 被空卷挂载时自动从镜像默认配置恢复。
set -e

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
DBS_HOME=$(cd "$SCRIPT_DIR/.." && pwd)

mkdir -p "$DBS_HOME/conf" "$DBS_HOME/logs" "$DBS_HOME/data" "$DBS_HOME/plugins"

CONFIG_PATH="$DBS_HOME/conf/application.properties"
if [ ! -f "$CONFIG_PATH" ]; then
    if [ ! -f /app/default-conf/application.properties ]; then
        echo "ERROR: conf/application.properties missing and no image default config found" >&2
        exit 1
    fi
    cp /app/default-conf/application.properties "$CONFIG_PATH"
    [ -f /app/default-conf/dbdatasync.p12 ] && cp /app/default-conf/dbdatasync.p12 "$DBS_HOME/conf/dbdatasync.p12"
    echo "[startup] conf 为空（卷挂载覆盖），已从镜像默认配置恢复"
fi

JRE_HOME_DIR="${JAVA_HOME:-/opt/jre8}"
JAVA_EXT_DIR="$JRE_HOME_DIR/lib/ext"
if [ ! -d "$JAVA_EXT_DIR" ]; then
    echo "ERROR: JRE lib/ext not found at $JAVA_EXT_DIR (Java 8 required)" >&2
    exit 1
fi
echo "[startup] DBS_HOME=$DBS_HOME"
echo "[startup] JAVA=$("$JRE_HOME_DIR/bin/java" -version 2>&1 | head -1)"
echo "[startup] JAVA_EXT_DIR=$JAVA_EXT_DIR"
echo "[startup] CONFIG=$CONFIG_PATH"

JAVA_OPTS=(
    "-Xms${DBS_HEAP_MIN:-256m}"
    "-Xmx${DBS_HEAP_MAX:-1024m}"
    "-Xss512k"
    "-XX:MetaspaceSize=128m"
    "-XX:MaxMetaspaceSize=384m"
    "-XX:MaxDirectMemorySize=${DBS_DIRECT_MEM:-256m}"
    "-XX:+DisableAttachMechanism"
    "-XX:+HeapDumpOnOutOfMemoryError"
    "-XX:HeapDumpPath=$DBS_HOME/logs/heapdump.hprof"
    "-XX:ErrorFile=$DBS_HOME/logs/hs_err_pid_%p.log"
    "-Djava.ext.dirs=$JAVA_EXT_DIR:$DBS_HOME/lib"
    "-Dspring.config.location=$CONFIG_PATH"
    "-DLOG_HOME=$DBS_HOME/logs"
    "-Dsun.stdout.encoding=UTF-8"
    "-Dfile.encoding=UTF-8"
    "-Duser.timezone=Asia/Shanghai"
    "-Duser.dir=$DBS_HOME"
)

if [ -e "$DBS_HOME/bin/libdbdatasync.so" ]; then
    JAVA_OPTS=("-agentpath:$DBS_HOME/bin/libdbdatasync.so" "${JAVA_OPTS[@]}")
fi
if [ -n "${DBS_JAVA_OPTS:-}" ]; then
    for opt in $DBS_JAVA_OPTS; do JAVA_OPTS+=("$opt"); done
fi

echo "[startup] Starting DBDataSync Application..."
exec "$JRE_HOME_DIR/bin/java" "${JAVA_OPTS[@]}" org.dbdatasync.web.Application
