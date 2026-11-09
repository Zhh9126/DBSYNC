#!/bin/bash
# Wave27：真实库全链路 E2E 一键执行（CI 与本地共用入口）。
# 流水线：建表 → Trigger CDC 捕获 → 事件还原 → 目标端真实应用 → 两端一致性校验 → 切流真实动作 + SQL。
# 默认以 H2 文件库作为真实库实跑（无外部依赖）；外部 MySQL/PG/Oracle 连接串提供后，
# 通过 E2E_PROPS 环境变量指向含 e2e.vendor=jdbc 连接信息的 properties 文件即可按同一条流水线执行。
# 跳过：SKIP_E2E=1 ./tools/e2e/run_real_pipeline_e2e.sh

set -e
cd "$(dirname "$0")/../.."   # 仓库根目录

if [ "${SKIP_E2E:-0}" = "1" ]; then
    echo "SKIP_E2E=1，跳过真库全链路 E2E"
    exit 0
fi

MAVEN_BIN="${MAVEN_BIN:-mvn}"

# Wave23 内存治理：小堆 + SerialGC + 仅 C1，构建不挤占物理内存
export MAVEN_OPTS="${MAVEN_OPTS:--Xms64m -Xmx512m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8}"

MODULES="dbdatasync-sdk,dbdatasync-biz,dbdatasync-connector/dbdatasync-connector-h2,dbdatasync-connector/dbdatasync-connector-sqlite"

echo "[1/4] 编译 E2E 依赖模块（跳过测试）..."
"$MAVEN_BIN" -q package -DskipTests -pl "$MODULES" -am

echo "[2/4] 构造 E2E classpath..."
"$MAVEN_BIN" -q dependency:build-classpath -Dmdep.outputFile=target/e2e_cp.txt -pl "$MODULES" -am

CP=""
SEP=";"
case "$(uname -s)" in
    Darwin|Linux*) SEP=":" ;;
esac
for m in dbdatasync-sdk dbdatasync-biz \
         dbdatasync-connector/dbdatasync-connector-h2 \
         dbdatasync-connector/dbdatasync-connector-sqlite; do
    CP="$CP$m/target/classes$SEP"
done
DEP_CP=$(cat dbdatasync-biz/target/e2e_cp.txt)
CP="${CP}${DEP_CP}"

echo "[3/4] 编译 E2E harness..."
mkdir -p target/e2e_classes
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
"$JAVAC" -encoding UTF-8 -cp "$CP" tools/e2e/RealDbPipelineE2e.java -d target/e2e_classes

echo "[4/4] 执行真库全链路 E2E..."
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if [ -n "${E2E_PROPS:-}" ]; then
    "$JAVA" -cp "target/e2e_classes$SEP$CP" RealDbPipelineE2e "$E2E_PROPS"
else
    # 默认 H2 文件库 vendor（临时目录，跑完即弃）
    PROPS="$(mktemp -t e2e_h2_XXXX).properties"
    printf 'e2e.vendor=h2\n' > "$PROPS"
    "$JAVA" -cp "target/e2e_classes$SEP$CP" RealDbPipelineE2e "$PROPS"
fi

echo "真库全链路 E2E 通过。"
