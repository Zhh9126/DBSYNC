@echo off

for %%F in ("%~dp0\..\") do set "DBS_HOME=%%~dpF"
echo DBS_HOME=%DBS_HOME%
cd /d "%DBS_HOME%"

if not exist "%DBS_HOME%conf\application.properties" (
  echo ERROR: conf\application.properties not found. Please unzip the whole package first.
  exit /b 1
)
if not exist "%DBS_HOME%logs" mkdir "%DBS_HOME%logs"

rem Auto-detect embedded jre8 in the same directory
if not defined JRE_HOME (
  if exist "%DBS_HOME%jre8" (
    set "JRE_HOME=%DBS_HOME%jre8"
    rem %JRE_HOME% inside this block would expand before the set above (delayed expansion), so echo the path directly.
    echo Using embedded JRE8: %DBS_HOME%jre8
  )
)

rem Determine java path: prefer jre8, otherwise use system java
if defined JRE_HOME (
  if exist "%JRE_HOME%\bin\java.exe" (
    set "JAVA_BIN=%JRE_HOME%\bin\java.exe"
  ) else if exist "%JRE_HOME%\jre\bin\java.exe" (
    set "JAVA_BIN=%JRE_HOME%\jre\bin\java.exe"
  ) else (
    set "JAVA_BIN=java"
  )
) else (
  set "JAVA_BIN=java"
)

rem Determine JAVA_EXT_DIR
if defined JRE_HOME (
  if exist "%JRE_HOME%\lib\ext" (
    set "JAVA_EXT_DIR=%JRE_HOME%\lib\ext"
  ) else if exist "%JRE_HOME%\jre\lib\ext" (
    set "JAVA_EXT_DIR=%JRE_HOME%\jre\lib\ext"
  )
) else (
  rem Fallback: try to find via system java
  for /f "delims=" %%i in ('where java 2^>nul') do set "JAVA_PATH=%%~dpi"
  if defined JAVA_PATH (
    set "JAVA_EXT_DIR=%JAVA_PATH%jre\lib\ext"
    if not exist "%JAVA_EXT_DIR%" set "JAVA_EXT_DIR=%JAVA_PATH%lib\ext"
  )
)

if not exist "%JAVA_EXT_DIR%" (
  echo ERROR: Cannot find JRE lib\ext. Please ensure JRE8 is installed or set JRE_HOME environment variable.
  exit /b 1
)
echo JAVA_EXT_DIR=%JAVA_EXT_DIR%

rem Wave29: offline machines are often 2~4GB RAM. Old fixed 3.8g heap failed to start there.
rem Small heap by default; scale up with DBS_HEAP_MIN / DBS_HEAP_MAX when data volume requires it.
if not defined DBS_HEAP_MIN set "DBS_HEAP_MIN=128m"
if not defined DBS_HEAP_MAX set "DBS_HEAP_MAX=768m"
if not defined DBS_DIRECT_MEM set "DBS_DIRECT_MEM=256m"
set "SERVER_OPTS=-Xms%DBS_HEAP_MIN% -Xmx%DBS_HEAP_MAX% -Xss512k -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=384m -XX:MaxDirectMemorySize=%DBS_DIRECT_MEM% -XX:+DisableAttachMechanism"
echo Heap: -Xms%DBS_HEAP_MIN% -Xmx%DBS_HEAP_MAX% (override with DBS_HEAP_MIN / DBS_HEAP_MAX)
rem set IPv4
rem set SERVER_OPTS=%SERVER_OPTS% -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv4Addresses

set "ENCRYPT_FILE=%DBS_HOME%bin\libdbdatasync.dll"
if exist "%ENCRYPT_FILE%" (
  set "SERVER_OPTS=%SERVER_OPTS% -agentpath:%ENCRYPT_FILE%"
)

set "SERVER_OPTS=%SERVER_OPTS% -Djava.ext.dirs="%JAVA_EXT_DIR%;%DBS_HOME%lib""
set "SERVER_OPTS=%SERVER_OPTS% -Dspring.config.location=%DBS_HOME%conf\application.properties"
rem log4j2.xml reads ${sys:LOG_HOME}; the old LOG_PATH was silently ignored.
set "SERVER_OPTS=%SERVER_OPTS% -DLOG_HOME=%DBS_HOME%logs"
set "SERVER_OPTS=%SERVER_OPTS% -Dsun.stdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -Duser.dir=%DBS_HOME%"
rem Wave29: no forced CMS / verbose GC (small heap + verbose GC floods offline disks).
rem JDK8 default collector is used; append custom flags via DBS_JAVA_OPTS.
if defined DBS_JAVA_OPTS set "SERVER_OPTS=%SERVER_OPTS% %DBS_JAVA_OPTS%"

rem Note: JRE does not support HeapDump and ErrorFile parameters, disabled.
rem set "SERVER_OPTS=%SERVER_OPTS% -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=%DBS_HOME%logs -XX:ErrorFile=%DBS_HOME%logs\hs_err.log"

rem Wave31-fix: Spring Boot relaxed binding maps BOTH SERVER_PORT and SERVER__PORT to
rem server.port, and OS env vars rank HIGHER than conf\application.properties. Dev shells
rem (WorkBuddy/CodeBuddy/CI) inject SERVER__PORT=<random session port>, which silently
rem moved the bind port or clashed with the owning process. Clear them, then pass the
rem conf port explicitly as a command-line arg (highest priority, immune to env pollution).
if defined SERVER_PORT echo WARN: SERVER_PORT=%SERVER_PORT% found in environment, cleared to use conf server.port.
set "SERVER_PORT="
if defined SERVER__PORT echo WARN: SERVER__PORT=%SERVER__PORT% found in environment, cleared to use conf server.port.
set "SERVER__PORT="

rem Read the desired port from conf and auto-avoid busy ports (+1, up to 20 tries),
rem so the launcher never fails just because 18686 happens to be taken.
set "CONF_PORT="
for /f "tokens=2 delims==" %%P in ('findstr /b /l /c:"server.port=" "%DBS_HOME%conf\application.properties"') do set "CONF_PORT=%%P"
if not defined CONF_PORT set "CONF_PORT=18686"
set "USE_PORT=%CONF_PORT%"
set /a PORT_TRIES=0
:find_free_port
netstat -ano | findstr /c:":%USE_PORT% " | findstr /i "LISTENING" >nul 2>&1
if errorlevel 1 goto port_ready
set /a USE_PORT+=1
set /a PORT_TRIES+=1
if %PORT_TRIES% geq 20 goto port_giveup
goto find_free_port
:port_giveup
echo ERROR: no free port found near %CONF_PORT% (tried 20). Free the port or edit conf\application.properties.
exit /b 1
:port_ready
if not "%USE_PORT%"=="%CONF_PORT%" echo WARN: port %CONF_PORT% is busy, using %USE_PORT% instead.
echo Using port: %USE_PORT% (from conf server.port=%CONF_PORT%, busy ports auto-avoided)

echo %SERVER_OPTS%
"%JAVA_BIN%" %SERVER_OPTS% org.dbdatasync.web.Application --server.port=%USE_PORT%