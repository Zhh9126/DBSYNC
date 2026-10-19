#!/bin/bash
# DBDataSync 一键打包：在本仓库根目录执行，产物为根目录下的 DBDataSync-{version}-bin.zip
# 依赖：JDK 1.8+、Maven 3.6+

mvn clean -f pom.xml
mvn package -f pom.xml -Dmaven.test.skip=true -Dskip.spotless=true

CURRENT_DIR=$(pwd);
mv "$CURRENT_DIR/dbdatasync-web/target/DBDataSync-"*.zip "$CURRENT_DIR" 2>/dev/null
echo "Done. ZIP at: $CURRENT_DIR/DBDataSync-*.zip"
