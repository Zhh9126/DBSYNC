#!/bin/bash
# DBDataSync 离线 Docker 镜像一键构建（零联网，参考 AIDBM 增量构建）
# 用法：bash docker/build-image.sh
# 可覆盖：APP_DIR（已验证的运行包）、JRE_DIR（JRE8 目录）、SRC（源码根目录）、
#         CTX（构建上下文）、IMAGE / LATEST（镜像 tag）、VERSION
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="${SRC:-$(cd "$HERE/.." && pwd)}"
APP_DIR="${APP_DIR:-/datasync/app/DBDataSync-2.1.4}"
JRE_DIR="${JRE_DIR:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
CTX="${CTX:-/tmp/dbs-image-build}"
VERSION="${VERSION:-2.1.4}"
DATE_TAG="${DATE_TAG:-$(date +%Y%m%d)}"
IMAGE="${IMAGE:-ghcr.io/zhh9126/dbdatasync:${VERSION}-sqlite-${DATE_TAG}}"
LATEST="${LATEST:-ghcr.io/zhh9126/dbdatasync:latest}"

echo "=== 构建参数 ==="
echo "SRC=$SRC"
echo "APP_DIR=$APP_DIR"
echo "JRE_DIR=$JRE_DIR"
echo "CTX=$CTX"
echo "IMAGE=$IMAGE"

[ -x "$JRE_DIR/bin/java" ] || { echo "ERROR: JRE8 不存在: $JRE_DIR" >&2; exit 1; }
[ -d "$JRE_DIR/lib/ext" ] || { echo "ERROR: JRE8 缺少 lib/ext（Java 8 硬要求）" >&2; exit 1; }
[ -f "$APP_DIR/lib/DBDataSync-web-${VERSION}.jar" ] || { echo "ERROR: 运行包不完整: $APP_DIR" >&2; exit 1; }
docker image inspect python:3.11-slim-bookworm >/dev/null 2>&1 || {
    echo "ERROR: 本机缺少 python:3.11-slim-bookworm（离线构建需先 docker load 基础镜像）" >&2; exit 1; }

echo "=== 组装构建上下文（只取发行内容，排除 data/logs/tmp.pid 等运行时产物）==="
find "$CTX" -mindepth 1 -delete 2>/dev/null || true
mkdir -p "$CTX/base/jre8" "$CTX/base/tz" "$CTX/app/app/tools" "$CTX/app/default-conf"

cp -a "$JRE_DIR/." "$CTX/base/jre8/"
# RPM 版 JRE8 有两个指向宿主系统目录的符号链接（lib/tzdb.dat、lib/security/cacerts），
# 原样拷进镜像会悬空：tzdb.dat 缺失导致 ZoneInfoFile 初始化崩溃，cacerts 缺失导致 SSL 不可用。
# 这里用解引用后的真实文件替换，JRE 内部相对链接（如 libjsig.so）保持不变。
for rel in lib/tzdb.dat lib/security/cacerts; do
    if [ -L "$CTX/base/jre8/$rel" ]; then
        src="$(readlink -f "$JRE_DIR/$rel")"
        [ -f "$src" ] || { echo "ERROR: JRE8 链接目标缺失: $src" >&2; exit 1; }
        find "$CTX/base/jre8/$rel" -delete
        cp "$src" "$CTX/base/jre8/$rel"
        echo "[base] 已实体化 JRE8 链接: $rel"
    fi
done
cp /usr/share/zoneinfo/Asia/Shanghai "$CTX/base/tz/Shanghai"
cp "$HERE/Dockerfile.base" "$CTX/base/Dockerfile"

cp -a "$APP_DIR/bin" "$APP_DIR/conf" "$APP_DIR/lib" "$CTX/app/app/"
cp "$APP_DIR/README-OFFLINE.md" "$CTX/app/app/"
cp "$APP_DIR/conf/application.properties" "$CTX/app/default-conf/"
[ -f "$APP_DIR/conf/dbdatasync.p12" ] && cp "$APP_DIR/conf/dbdatasync.p12" "$CTX/app/default-conf/"
cp "$HERE/startup-docker.sh" "$CTX/app/app/bin/startup-docker.sh"
cp "$HERE/h2_to_sqlite_docker.sh" "$CTX/app/app/tools/h2_to_sqlite.sh"
cp "$SRC/tools/H2ToSqlite.java" "$CTX/app/app/tools/"
cp "$HERE/Dockerfile.app" "$CTX/app/Dockerfile"

echo "=== 预编译 H2 迁移器（镜像仅含 JRE，无 javac）==="
javac -encoding UTF-8 -cp "$APP_DIR/lib/*" -d "$CTX/app/app/tools" "$SRC/tools/H2ToSqlite.java"
chmod +x "$CTX/app/app/bin/startup-docker.sh" "$CTX/app/app/tools/h2_to_sqlite.sh"
echo "上下文大小: $(du -sh "$CTX" | cut -f1)"

echo "=== 构建基座（JRE8）==="
docker build -t dbdatasync-base:8 -f "$HERE/Dockerfile.base" "$CTX/base"

echo "=== 构建应用层（增量，零联网）==="
docker build -t "$IMAGE" -t "$LATEST" --build-arg "DBS_VERSION=$VERSION" -f "$HERE/Dockerfile.app" "$CTX/app"

echo "=== 完成 ==="
docker images --format '{{.Repository}}:{{.Tag}}|{{.ID}}|{{.Size}}' | grep -E 'dbdatasync' || true
