@echo off
rem DBDataSync 一键打包：在本仓库根目录执行，产物为根目录下的 DBDataSync-{version}-bin.zip
rem 依赖：JDK 1.8+、Maven 3.6+（首次构建需联网或离线私服）

echo "Clean Project ..."
call mvn clean -f pom.xml

echo "Build Project ..."
call mvn package -f pom.xml -D"maven.test.skip=true" -D"skip.spotless=true"

set CP_PATH=%~dp0
move %CP_PATH%dbdatasync-web\target\DBDataSync-*.zip %CP_PATH%
echo Done. ZIP at: %CP_PATH%DBDataSync-*.zip

:exit
