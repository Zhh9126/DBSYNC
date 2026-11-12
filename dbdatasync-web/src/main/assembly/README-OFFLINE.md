# DBDataSync 离线运行说明

本发行包用于**无外网环境**：所有 Java 依赖、JDBC 驱动、前端字体/图标/脚本、操作手册均随包提供，
启动与运行过程不访问外网、不依赖 Maven、不需要 CDN。

## 1. 目录结构

```
DBDataSync-2.1.4/
├── bin/         启动 / 停止脚本（startup.bat、startup.sh、stop.sh）
├── conf/        application.properties、dbdatasync.p12（HTTPS 证书，默认关闭 SSL）
├── lib/         全部运行依赖与各数据库 JDBC 驱动（MySQL/PG/Oracle/SQLServer/达梦/H2/SQLite/ClickHouse/Mongo/Kafka 等）
├── jre8/        （仅 offline-win64 包）内嵌 Windows 64 位 JRE 8，无需目标机安装 Java
├── logs/        首次启动自动创建：info.log / warn.log / error.log
├── data/        首次启动自动创建：SQLite 元数据库 DBDataSync.db（含 -wal/-shm）、运行时持久化目录
└── README-OFFLINE.md
```

## 2. 环境要求

- **Java 8 是硬要求**：启动脚本使用 `-Djava.ext.dirs`，该参数在 JDK 9+ 已被移除。
  Windows offline 包已自带 `jre8`，直接用即可；通用包请在目标机安装 JDK/JRE 8，或把对应平台的 `jre8` 目录放到本目录下。
- 内存：默认 `-Xms128m -Xmx768m`，2GB 内存机器即可启动。数据量大时按下表调大，不需要一开始就分配数 GB。
- 端口：默认 `18686`（HTTP，SSL 默认关闭）。若环境里存在 `SERVER__PORT` 之类变量，Spring Boot 会把它识别成 `server.port`，启动前请 `unset SERVER__PORT`（Linux）或删除该环境变量（Windows）。

## 3. 启动

- **Windows（offline 包）**：双击 `bin\startup.bat`，或命令行执行 `bin\startup.bat`。脚本自动发现同目录 `jre8`。
- **Linux / macOS（通用包）**：`cd DBDataSync-2.1.4 && ./bin/startup.sh`。
  若提示找不到 `JRE lib/ext`，说明系统 Java 不是 8 或未安装，请把 Linux 版 JRE 8 解压为同目录 `jre8/` 后重试。
- 启动后日志出现 `Tomcat started on port(s): 18686` 即成功。浏览器访问 `http://127.0.0.1:18686`，
  未登录会跳转登录页；默认账号 `admin/admin`，**首次登录请立即在【系统管理 → 用户管理】修改密码**。
- 停止：Windows 直接关闭窗口 / `Ctrl+C`；Linux 执行 `./bin/stop.sh`。
- 内置操作手册：`http://127.0.0.1:18686/manual`（菜单【系统管理 → 操作手册】），离线可查，包含各功能与配置项说明。

## 4. 启动参数（环境变量，可选）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `DBS_HEAP_MIN` | `128m` | JVM 初始堆 |
| `DBS_HEAP_MAX` | `768m` | JVM 最大堆；大表 / 高并发任务按实际需要调大 |
| `DBS_DIRECT_MEM` | `256m` | 堆外内存上限 |
| `DBS_JAVA_OPTS` | 空 | 追加 JVM 参数，如 `-XX:+UseG1GC -verbose:gc` |
| `JRE_HOME` | 自动发现 `jre8/` | 显式指定 JRE 8 目录 |
| `DBDATASYNC_H2_PASSWORD` | 空 | 仅当回退使用 `type=h2` 时有效；SQLite 无口令，默认部署不用设置 |
| `DBDATASYNC_SSL_KEYSTORE_PASSWORD` | `DBDataSync` | 启用 SSL 时的证书口令 |

修改 `conf/application.properties` 可切换元数据库（**默认本地 SQLite** `data/DBDataSync.db`，无需外部数据库；可回退 `h2` / 切换 `mysql`）、端口、线程池等；修改后重启生效。
**从旧版 H2 升级必须先停服并运行 `bash tools/h2_to_sqlite.sh` 做一次性数据迁移**，否则新库为空（详见手册 19.11）。集群 HA、checkpoint 等需要共享目录的能力，按需按手册第 12 章配置。

## 5. 升级与备份

1. 停止服务；
2. **停服后**备份整个 `data/` 目录与 `conf/application.properties`（SQLite WAL 模式下需确保 `.db` / `.db-wal` / `.db-shm` 一起备份）；
3. 解压新包到新目录，把备份的 `data/` 与 `conf/application.properties` 覆盖回去；
4. 启动并到【配置管理】核对配置、【监控告警 → 链路健康】确认链路正常。

> 注意：`lib/` 必须整体替换，不要只替换单个 jar；旧包与新包的 `lib/` 不要混用。

## 6. 离线安全基线

- 修改默认 admin 口令；
- 保持 `dbdatasync.web.security.reset-pwd=false`；
- 可在【系统配置】开启「严格安全模式」（`enableStrictSecurity`）；
- 端口仅对内网开放，日志与 `data/` 目录按最小权限授权。
