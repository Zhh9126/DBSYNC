#!/bin/bash
# DBDataSync 一键打包：在本仓库根目录执行，产物为根目录下的 DBDataSync-{version}-bin.zip
# 依赖：JDK 1.8+、Maven 3.6+

# Wave23 P2：JDK8 默认按「物理内存 / 4」预留堆，32GB 机器会给 Maven 自身预留约 8GB，
# 叠加各模块测试 fork 后容易把 Windows 提交内存 / 页面文件挤满。构建不需要大堆，
# 这里显式收窄 Maven JVM；测试 fork 的参数由父 pom 的 surefire argLine 统一控制。
export MAVEN_OPTS="${MAVEN_OPTS:--Xms64m -Xmx512m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8}"

# 文档同步校验：功能变更必须同步更新内置操作手册（见手册第 18 章维护约定）。
# 没有 Python 的环境只提示不阻断；需要临时跳过用 SKIP_DOCS_CHECK=1 ./build.sh。
if [ "${SKIP_DOCS_CHECK:-0}" != "1" ]; then
    PY_BIN=""
    if command -v python3 >/dev/null 2>&1; then
        PY_BIN=python3
    elif command -v python >/dev/null 2>&1; then
        PY_BIN=python
    fi
    if [ -n "$PY_BIN" ]; then
        "$PY_BIN" tools/check_manual_sync.py || {
            echo "文档同步校验未通过：请先更新 public/manual/index.html（SKIP_DOCS_CHECK=1 可临时跳过）" >&2
            exit 1
        }
    else
        echo "WARN: 未找到 Python，跳过文档同步校验（CI 仍会执行）" >&2
    fi
fi

mvn clean -f pom.xml
# Wave23：默认执行单测；只有显式 SKIP_TESTS=1 才跳过（CI 与本地一致）
TEST_ARGS=""
if [ "${SKIP_TESTS:-0}" = "1" ]; then
    TEST_ARGS="-Dmaven.test.skip=true"
fi
mvn package -f pom.xml $TEST_ARGS -Dskip.spotless=true

# Wave27：真库全链路 E2E（H2 vendor，无外部依赖）。SKIP_E2E=1 可跳过；
# 外部库连接串用 E2E_PROPS 指向 properties 文件后按同一流水线执行。
./tools/e2e/run_real_pipeline_e2e.sh

CURRENT_DIR=$(pwd);
mv "$CURRENT_DIR/dbdatasync-web/target/DBDataSync-"*.zip "$CURRENT_DIR" 2>/dev/null
echo "Done. ZIP at: $CURRENT_DIR/DBDataSync-*.zip"
