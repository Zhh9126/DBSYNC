#!/bin/bash

# 获取目录路径
SCRIPT_DIR=$(cd "$(dirname "$0")"; pwd)
DBS_HOME=$(cd "$SCRIPT_DIR/.."; pwd)
echo "DBS_HOME=$DBS_HOME"

# Wave29 离线交付修复：H2 的 jdbc:h2:file:./data/... 以进程工作目录为基准，
# 不切到安装目录时数据文件会散落在调用者目录里。
cd "$DBS_HOME" || { echo "ERROR: cannot enter $DBS_HOME" >&2; exit 1; }

# 自动检测同目录下的 jre8（离线包内嵌运行时）。
# Wave29：仅当存在本平台可执行的 bin/java 才采用。离线包里的 Windows 版 jre8
# 只有 java.exe，在 Linux 上会被跳过并回退系统 Java，避免误用不可执行的运行时。
if [[ -z "${JRE_HOME:-}" && -d "$DBS_HOME/jre8" ]]; then
  if [[ -x "$DBS_HOME/jre8/bin/java" ]]; then
    export JRE_HOME="$DBS_HOME/jre8"
    echo "Using embedded JRE8: $JRE_HOME"
  else
    echo "WARN: embedded jre8 is not runnable on this platform, falling back to system Java (JDK/JRE 8 required)"
  fi
fi

# 确保 conf 目录存在
if [ ! -d "$DBS_HOME/conf" ]; then
  mkdir -p "$DBS_HOME/conf"
fi

# 确保 logs 目录存在
if [ ! -d "$DBS_HOME/logs" ]; then
  mkdir -p "$DBS_HOME/logs"
fi

# application.properties
CONFIG_PATH="$DBS_HOME/conf/application.properties"
if [ ! -f "$CONFIG_PATH" ]; then
  echo "The conf/application.properties doesn't exist, please check it first!"
  exit 1
fi

# check process
APP="org.dbdatasync.web.Application"
PROCESS=$(pgrep -f "${APP}")
if [[ -n "$PROCESS" ]]; then
  echo "The app already started (PID: $PROCESS)."
  exit 1
fi

###########################################################################
# JRE 扩展目录：须包含 sunjce_provider，否则 TLS 报 SunTls12RsaPremasterSecret KeyGenerator not available
resolve_jre_ext_dir() {
  # 优先使用 JRE_HOME
  if [[ -n "${JRE_HOME:-}" ]]; then
    if [[ -d "$JRE_HOME/lib/ext" ]]; then
      echo "$JRE_HOME/lib/ext"
      return
    fi
    if [[ -d "$JRE_HOME/jre/lib/ext" ]]; then
      echo "$JRE_HOME/jre/lib/ext"
      return
    fi
  fi

  # 回退：查找系统 Java
  local java_home_prop
  if ! command -v java >/dev/null 2>&1; then
    echo ""
    return
  fi
  java_home_prop=$(java -XshowSettings:properties -version 2>&1 | tr -d '\r' | sed -n 's/^ *java\.home = //p' | head -1)
  if [[ -n "$java_home_prop" && -d "$java_home_prop/lib/ext" ]]; then
    echo "$java_home_prop/lib/ext"
    return
  fi

  echo ""
}

JAVA_EXT_DIR=$(resolve_jre_ext_dir)
if [[ -z "$JAVA_EXT_DIR" ]]; then
  echo "ERROR: Cannot find JRE lib/ext (JCE required for SSL). Set JAVA_HOME or fix PATH to java." >&2
  exit 1
fi
echo "JAVA_EXT_DIR=$JAVA_EXT_DIR"

###########################################################################
# 构建 JVM 参数
JAVA_OPTS=()

ENCRYPT_FILE=''
if [[ $(uname -m) == "aarch64"* ]]; then
   ENCRYPT_FILE='libDBDataSync_aarch64.so'
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
   ENCRYPT_FILE='libdbdatasync.so'
elif [[ "$OSTYPE" == "darwin"* ]]; then
   ENCRYPT_FILE='libdbdatasync.dylib'
else
  echo "Unsupported OS." >&2
  exit 1
fi

# 1. 内存与基础配置 (放在最前面)
# Wave29：离线环境常见 2~4GB 小内存机器，旧版固定 3g 堆会直接启动失败。
# 默认小堆起步，按实际数据量用 DBS_HEAP_MIN / DBS_HEAP_MAX 放大，不再「多分配内存」硬扛。
DBS_HEAP_MIN="${DBS_HEAP_MIN:-128m}"
DBS_HEAP_MAX="${DBS_HEAP_MAX:-768m}"
JAVA_OPTS+=("-Xms$DBS_HEAP_MIN")
JAVA_OPTS+=("-Xmx$DBS_HEAP_MAX")
JAVA_OPTS+=("-Xss512k")
JAVA_OPTS+=("-XX:MetaspaceSize=128m")
JAVA_OPTS+=("-XX:MaxMetaspaceSize=384m")
JAVA_OPTS+=("-XX:MaxDirectMemorySize=${DBS_DIRECT_MEM:-256m}")
JAVA_OPTS+=("-XX:+DisableAttachMechanism")

# 2. GC 与故障诊断：小堆下不再强制 G1 + 16m region（会把堆切成过少的分区），
# 使用 JDK8 默认收集器；GC 细节通过 DBS_JAVA_OPTS 按需追加。
JAVA_OPTS+=("-XX:+HeapDumpOnOutOfMemoryError")
JAVA_OPTS+=("-XX:HeapDumpPath=$DBS_HOME/logs/heapdump.hprof")
JAVA_OPTS+=("-XX:ErrorFile=$DBS_HOME/logs/hs_err_pid_%p.log")

# 3. Agent (如果有)
if [ -e "$DBS_HOME/bin/$ENCRYPT_FILE" ]; then
  JAVA_OPTS+=("-agentpath:$DBS_HOME/bin/$ENCRYPT_FILE")
fi

# 4. 系统属性
JAVA_OPTS+=("-Djava.ext.dirs=$JAVA_EXT_DIR:$DBS_HOME/lib")
JAVA_OPTS+=("-Dspring.config.location=$CONFIG_PATH")
JAVA_OPTS+=("-DLOG_HOME=$DBS_HOME/logs")
JAVA_OPTS+=("-Dsun.stdout.encoding=UTF-8")
JAVA_OPTS+=("-Dfile.encoding=UTF-8")
JAVA_OPTS+=("-Duser.timezone=Asia/Shanghai")
JAVA_OPTS+=("-Duser.dir=$DBS_HOME")

# 5. 主类
APP="org.dbdatasync.web.Application"

# 6. 追加自定义 JVM 参数（例如 -XX:+UseG1GC -verbose:gc），空格分隔
if [[ -n "${DBS_JAVA_OPTS:-}" ]]; then
    read -r -a DBS_EXTRA_OPTS <<< "$DBS_JAVA_OPTS"
    JAVA_OPTS+=("${DBS_EXTRA_OPTS[@]}")
fi
echo "Heap: -Xms$DBS_HEAP_MIN -Xmx$DBS_HEAP_MAX (DBS_HEAP_MIN / DBS_HEAP_MAX 可覆盖)"

# 确定 java 路径：优先 jre8，否则使用系统 java
if [[ -n "${JRE_HOME:-}" ]]; then
  if [[ -x "$JRE_HOME/bin/java" ]]; then
    JAVA_BIN="$JRE_HOME/bin/java"
  elif [[ -x "$JRE_HOME/jre/bin/java" ]]; then
    JAVA_BIN="$JRE_HOME/jre/bin/java"
  else
    JAVA_BIN="java"
  fi
else
  JAVA_BIN="java"
fi

# execute command
echo "Starting DBDataSync Application..."

# Wave31-fix: Spring Boot relaxed binding maps BOTH SERVER_PORT and SERVER__PORT to
# server.port, and OS env vars rank HIGHER than conf/application.properties. Dev shells
# (WorkBuddy/CodeBuddy/CI) inject SERVER__PORT=<random session port>, which silently
# moved the bind port. Clear them, resolve a free port (auto-avoid +1, up to 20 tries)
# and pass it explicitly as a command-line arg (highest priority, immune to env pollution).
if [[ -n "${SERVER_PORT:-}" ]]; then
  echo "WARN: SERVER_PORT=$SERVER_PORT found in environment, cleared to use conf server.port."
  unset SERVER_PORT
fi
if [[ -n "${SERVER__PORT:-}" ]]; then
  echo "WARN: SERVER__PORT=$SERVER__PORT found in environment, cleared to use conf server.port."
  unset SERVER__PORT
fi

CONF_PORT=$(grep -E '^server\.port=' "$CONFIG_PATH" | tail -1 | cut -d= -f2 | tr -d ' \r')
USE_PORT="${CONF_PORT:-18686}"
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  if ! (netstat -an 2>/dev/null || true) | grep -E "[:.]${USE_PORT}[[:space:]]" | grep -qi listen; then
    break
  fi
  echo "WARN: port ${USE_PORT} is busy, trying next..."
  USE_PORT=$((USE_PORT + 1))
done
if [[ "$USE_PORT" != "${CONF_PORT:-18686}" ]]; then
  echo "WARN: conf port ${CONF_PORT:-18686} is busy, using ${USE_PORT} instead."
fi
echo "Using port: ${USE_PORT} (busy ports auto-avoided)"

nohup "$JAVA_BIN" "${JAVA_OPTS[@]}" "$APP" --server.port="$USE_PORT" > /dev/null 2>&1 &
APP_PID=$!

# 保存 PID 并反馈结果
echo "$APP_PID" > "$DBS_HOME/tmp.pid"
echo "Start successfully! PID: $APP_PID"
echo "Note: Please check $DBS_HOME/logs for detailed output."