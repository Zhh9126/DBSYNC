# DBDataSync 产品白皮书

> 版本：2.x ｜ 文档性质：产品与技术白皮书

---

## 1. 产品概述

### 1.1 产品定位

DBDataSync（简称 dbs）是一款开源的**通用数据同步中间件**，以「组合驱动、任意搭配」为设计理念，支持关系型数据库、非关系型数据库、消息队列、文件、HTTP 等异构数据源之间的双向数据同步，覆盖**全量迁移、增量实时同步（CDC）、整库迁移、数据对比、数据血缘、性能监控、告警通知**等企业级数据集成场景。

产品坚持开箱即用：内置 H2 持久化、Web 可视化控制台、无需额外部署中间件，下载解压即可完成第一套同步任务的搭建。

### 1.2 核心价值

- **异构互联**：20+ 连接器开箱即用，任意组合「库 → 库」「库 → 消息队列」「消息队列 → 库」等同步拓扑。
- **全量 + 增量一体**：支持全量、增量、全量+增量三种同步方式，增量阶段自动捕获位点，先全量后增量的流程无缝衔接。
- **实时可控**：实时监控同步运行状态、TPS、统计结果，失败数据可查询、可重试、可订正。
- **强一致与高吞吐兼顾**：提供同步（SYNC，单线程串行，强一致）与异步（ASYNC，多线程并发写，高吞吐）两种执行模式，按业务场景自由切换。
- **可扩展**：SPI 连接器机制 + 转换插件热加载，业务定制零门槛。

---

## 2. 核心特性

| 能力 | 说明 |
|------|------|
| 多源连接 | 支持 MySQL、Oracle、SQLServer、PostgreSQL、达梦、金仓、OceanBase、TiDB、Doris、StarRocks、ClickHouse、DuckDB、H2、SQLite、MongoDB、Redis、Elasticsearch、Kafka、RocketMQ、File、Http 等 20+ 数据源 |
| 三种同步方式 | 全量（FULL）、增量（INCREMENT）、全量+增量（FULLINCREMENT，先全量后增量） |
| 两种执行模式 | 同步 SYNC（单线程串行、强一致）、异步 ASYNC（多线程并发写、高吞吐） |
| 两种增量监听 | LOG（日志/CDC 解析）、TIMING（定时轮询） |
| 字段级映射 | 同名映射、同行映射、手动编辑，支持过滤条件（9 种运算符）、字段转换（20 种规则） |
| 插件扩展 | 自定义转换插件上传热加载，同步转换逻辑灵活扩展 |
| 引擎 | 表级/SQL级数据同步，LL(1) 词法 SQL 引擎 + Insert/Update/Delete SQL 生成；支持多线程并发执行、目标端 group commit 批量写；可作为嵌入式 SDK 集成到任意 Java 应用 |
| 整库迁移 | 一键复制整个数据库：自动建库建表（A）+ 数据迁移 + 一键接续增量（B）+ 实例级一键全库映射（C）+ 新表自动发现（D），策略组合见 §4.2 |
| 数据对比 | 独立一级菜单；结构 / 行数据 / 索引 / 触发器 / 函数差异校验；全量逐行与抽样两种模式；差异自动生成修复 SQL（INSERT/UPDATE/DELETE，BLOB 十六进制字面量）；自动订正（默认关闭，显式开启）与手动订正、反向扫描清理；周期对比（cron）；整库迁移完成后一键创建对比任务 |
| 数据血缘 | 自动采集同步映射关系，可视化「源 → 目标」数据流，支持库级/表级粒度与连接器筛选 |
| 性能监控 | CPU/内存/磁盘/TPS 实时监控、同步数据与日志查询、失败重试管理 |
| 告警通知 | 邮件、HTTP、企业微信、钉钉多渠道告警，支持失败数据、连接离线、停止驱动等场景 |
| 定时调度 | 内置任务调度器，cron / interval 定时策略，支持时间占位符（如 `$timestamp_begin/end$`） |
| OpenAPI | 统一开放网关，API 密钥 + JWT + IP 白名单 + 时间戳防重放 + RSA 加解密 |
| 配置导入导出 | 全量配置打包导出 / 导入热刷新，环境迁移便捷 |
| 开箱即用 | 内置 H2 数据库，下载解压即用，默认端口 18686 |

---

## 3. 功能架构

```text
┌─────────────────────────────────────────────────────────────┐
│                       Web 控制台（Thymeleaf + Tailwind）      │
│   仪表盘 │ 连接管理 │ 数据同步 │ 数据库同步 │ 数据对比 │ 血缘    │
│   监控 │ 告警 │ 插件 │ 配置 │ 用户 │ 系统 │ OpenAPI           │
├─────────────────────────────────────────────────────────────┤
│                       业务服务层（biz）                        │
│   MappingService  TableGroupService  SyncService            │
│   ValidateSyncService  DatabaseSyncService  LineageService  │
│   NoticeService  PluginService  UserConfigService ...       │
├─────────────────────────────────────────────────────────────┤
│                   任务调度与同步引擎（manager）                 │
│   FullPuller（全量：分页游标 + 断点续传 + 表级并发）            │
│   IncrementPuller（增量：LOG 解析 / TIMING 轮询 + 失败重试）   │
│   FullIncrementPuller（全量+增量：位点捕获无缝衔接）            │
│   执行模式：SYNC 串行 / ASYNC 并发（FullPuller.getWriteExecutor）│
├─────────────────────────────────────────────────────────────┤
│                    解析与插件层（parser / plugin）             │
│   SQL 解析 │ 配置解析 │ Picker 字段选择 │ 转换/过滤/插件 SPI     │
├─────────────────────────────────────────────────────────────┤
│              连接器层（connector，SPI 机制）                   │
│   MySQL Oracle SQLServer PG DM Kingbase OceanBase TiDB ...  │
├─────────────────────────────────────────────────────────────┤
│                存储层（storage / sdk，内置 H2）                │
│   config │ user │ connector │ table_group │ meta │ log │    │
│   task │ task_detail │ lineage │ 全部 DBDataSync_ 前缀        │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. 功能详解

### 4.1 数据同步（核心模块）

数据同步是整个产品的核心，围绕「映射表 → 字段映射 → 过滤/转换/插件 → 定时调度 → 运行管理」的完整链路展开。

#### 4.1.1 同步方式

| 方式 | 说明 |
|------|------|
| 全量（FULL） | 将源表数据一次性全量同步到目标表，支持分页游标读取与断点续传 |
| 增量（INCREMENT） | 持续监听源端数据变更，实时同步新增/修改/删除/DDL 事件 |
| 全量+增量（FULLINCREMENT） | 先捕获源端位点，执行全量同步，再从位点启动增量，全程无缝隙 |

#### 4.1.2 执行模式

| 模式 | 实现 | 适用场景 |
|------|------|---------|
| 同步（SYNC） | 单线程串行写入（`Runnable::run`） | 对一致性要求高、数据量可控的场景 |
| 异步（ASYNC） | 多线程并发写入（固定线程池 2~4，daemon 线程） | 大数据量、追求吞吐的场景 |

> 说明：当同步批次大小（batchNum）小于单批读取量（readNum）时，异步模式会将一批数据拆分为多个并发写任务，最大化写入吞吐；实际并发度受 batchNum 控制。

#### 4.1.3 增量监听类型

| 类型 | 说明 |
|------|------|
| LOG（日志/CDC） | 解析源端日志（如 MySQL binlog、MongoDB oplog、Redis 增量等）实现变更捕获 |
| TIMING（定时轮询） | 按 cron / interval 定时抽取源端增量数据，可配置时间占位符 |

#### 4.1.4 字段映射

- **同名映射**：源目标字段名一致时一键生成映射关系。
- **同行映射**：按字段顺序自动对齐。
- **手动编辑**：行内修改 / 弹窗编辑任意源→目标字段对。
- **清空 / 重新生成**：支持一键重建映射。

#### 4.1.5 过滤条件

支持 9 种运算符：等于、不等于、大于、小于、大于等于、小于等于、`like` 模糊、`in` 集合、`is null / is not null`，可对源表数据按条件过滤后同步。

#### 4.1.6 字段转换

内置 20 种转换规则，按字段粒度配置：

- **取值类**：默认值、UUID、系统时间戳 / 日期
- **时间类**：Timestamp 转 Date / 中国标准时间 / Long，Long 转 Timestamp，String 转 Timestamp / 自定义格式日期
- **类型类**：Number 转 String、Byte[] 转 String
- **字符串类**：替换、前面/后面追加、去首/尾字符、截取 N 字符、清空

#### 4.1.7 插件扩展

- 上传自定义转换插件（jar），系统自动热加载。
- 同步过程中按表映射粒度挂载插件，`PluginExtInfo` 传递插件参数。
- 支持下载官方插件开发包（模板工程）快速开始二次开发。

#### 4.1.8 定时调度

- 增量 TIMING 模式支持 cron 表达式调度：`[秒] [分] [小时] [日] [月] [周]`。
- 内置时间占位符：`$timestamp_begin/end$`、`$date_begin/end$`、`$date_yes_begin/end$`、`$timestamp_yes_begin/end$` 等，自动替换为实际时间窗口。
- 全量模式亦可配置定时触发。

#### 4.1.9 同步事件开关

可独立启用/配置：修改（U）、插入（I）、删除（D）、DDL 事件，按需选择增量同步的事件类型。

#### 4.1.10 运行管理

- **启动 / 停止 / 复制 / 删除**：任务生命周期完整管理。
- **运行日志与事件**：同步事件页展示各表映射的执行明细。
- **同步统计**：每个表映射独立汇总成功/失败/总数（Meta 指标）。
- **失败处理**：失败数据可在监控模块查询详情并手动重试 / 同步。
- **预加载续跑**：系统重启后自动恢复运行中任务的同步进度。

### 4.2 数据库同步（整库迁移）

- **库级映射**：以「源库 → 目标库」为单位配置，支持选择库 + Schema。
- **结构迁移**：`enableCopySchema` 开关，`overwriteSchema` 控制目标已存在时的覆盖策略。
- **数据迁移**：`enableCopyData` 开关，`overwriteData` 控制数据覆盖策略。
- **明细追踪**：按「结构迁移（TABLE_SCHEMA）/ 数据迁移（ROW_DATA）」两阶段记录迁移结果明细，可滚动分页预览源库表清单。
- **独立任务管理**：整库迁移作为独立任务类型（DATABASE_SYNC），支持增删改查、启停与结果查看。
- **自动建库建表（A）**：目标库/目标表缺失时自动创建（MySQL 建库 + 按源端 DDL 建表，其余目标端建表），先建库再读元数据，避免连接池回退默认库。
- **一键接续增量（B）**：整库迁移完成后，列表页对已完成任务一键生成同库映射的增量驱动（Mapping），自动按同名字段建表映射与字段映射，创建即连 binlog/CDC 位点，`mappingIds` 回写任务可追溯；重复创建幂等提示。
- **实例级一键全库映射（C）**：新增映射时一键枚举源实例全部业务库（内置系统库过滤 + 排除库/已选库去重），批量生成「源库 → 目标库」映射卡片。
- **新表自动发现（D）**：`enableAutoDiscover` 开启后，任务每次启动扫描源端各库表清单，发现源端新增表自动建目标表（配合复制表结构）追加表映射并参与本轮迁移；视图与元数据不可读对象自动跳过，发现失败不阻断既有映射迁移。
- 详细对标与实现边界见 `E:\DBSYNC\reports\SEATUNNEL_ALIGN_MATRIX.md`。

### 4.3 数据对比（独立一级菜单）

> 对比（检测差异）与订正（修改数据）解耦：默认纯对比，只输出差异与修复 SQL；订正为差异发现后的显式动作。

- **校验范围可配**：表结构（enableSchema）、行数据（enableRowData）、索引（enableIndex）、触发器（enableTrigger）、函数（enableFunction）。
- **对比模式**：`full` 全量逐行（精确定位差异）；`sample` 抽样对比（`sampleStep` 每页跳读步长，大表快速健康检查，不定位具体行，抽样模式禁用自动订正）。
- **修复 SQL 自动生成**：每条差异携带可直接执行的 SQL——目标缺失→INSERT、数据不一致→UPDATE（源值覆盖差异列）、目标多余→DELETE；BLOB 生成 0x 十六进制字面量，字符串值自动转义。
- **差异订正**：单条差异「手动订正」（`manualRevise`，系统直接修正目标端数据）；创建任务时显式开启 `enableSync` 后，对比发现差异即自动订正（默认关闭）。
- **反向扫描**：`enableReverseScan` 检查目标库中源端不存在的多余行（批量 IN 存在性检查），`enableReverseSync` 自动清理。
- **周期对比**：触发方式 once / cron（timing），可按天/周自动执行。
- **一键对比**：整库迁移任务完成后，`POST /database-sync/createValidateSync` 或列表「一键数据对比」按钮，按迁移任务全部映射自动创建对比任务。
- **结果明细**：分页查看对比结果，单条差异详情展开（字段级 源值/目标值/差异说明 + 修复 SQL）。

### 4.4 数据血缘

- **自动采集**：同步映射创建/运行时自动构建库级、表级血缘边（`LineageEdge`），无需人工维护。
- **可视化**：「源 → 目标」数据流关系表 + 连接器统计卡片。
- **筛选维度**：粒度筛选（库级 DATABASE / 表级 TABLE / 全部）、连接器筛选（源/目标）。
- **生命周期**：删除同步任务时自动清理关联血缘数据。

### 4.5 性能监控

- **主机指标**：CPU 使用率、内存已用/空闲/总共、磁盘空间、系统运行环境（基于 Spring Boot Actuator + OSHI）。
- **实时曲线**：每 5s 采集 CPU/内存/磁盘历史数据（60 个采样点滚动）。
- **TPS 监控**：全量/增量同步 TPS 实时指标。
- **同步数据查询**：分页查询同步成功/失败数据，支持按需查看单条字段详情。
- **日志管理**：同步日志查询、一键清空。
- **失败重试**：重试列表页查看失败记录并手动重新同步。
- **清理策略**：每 30s 自动清理过期数据与日志（过期时间可在系统配置中设置）。

### 4.6 告警通知

- **通知渠道**：邮件（EMAIL）、HTTP、企业微信（WE_CHAT）、钉钉（DING_TALK）。
- **告警场景**：同步任务出现失败数据、驱动停止、连接离线、数据对比差异、系统消息。
- **通知类型**：测试消息、连接离线、同步任务失败记录、手动停止、数据对比差异、授权异常/到期提醒/已过期。
- **发送频率**：立即发送（IMMEDIATE）或周期发送（INTERVAL，cron 调度）。
- **测试能力**：保存配置后一键发送测试消息验证渠道可用性。

### 4.7 插件管理

- 插件上传（jar），自动校验并拷贝至插件目录，上传即热加载。
- 插件与 JDBC 驱动关联扫描，已加载连接器（驱动）列表可视化展示。
- 提供官方开发包下载，快速开始自定义转换插件开发。

### 4.8 连接管理

- **连接器类型**：MySQL、PostgreSQL、SQLite、SqlServer、Oracle、达梦 DM8、人大金仓 Kingbase、OceanBase、OceanBase(Oracle 模式)、TiDB、Doris、StarRocks、ClickHouse、DuckDB、H2、MongoDB、Redis、Elasticsearch、Kafka、RocketMQ、File、Http。
- **操作能力**：新增 / 编辑 / 复制 / 删除，支持分页搜索。
- **测试连接**：一键校验连接可用性。
- **健康状态**：连接健康状态自动刷新（每 10s）。
- **元数据浏览**：远程获取数据库 / Schema / 表列表，供选表使用。
- **位点信息**：获取增量同步位点，支撑断点续传。

### 4.9 配置管理

- **导出**：将系统全量配置打包导出为 zip（包含 system、user、connector、mapping、tableGroup、meta、task 等），雪花 ID 命名。
- **导入**：上传 zip 一键导入并热刷新，自动记录导入日志。
- **体积预估**：导出前展示各配置模型的条目数与体积估算。

### 4.10 用户与权限

- **角色模型**：管理员（admin）、普通用户（user）。
- **权限控制**：仅管理员可新增/删除/查看全部用户，普通用户仅能修改/查看本人信息。
- **密码安全**：登录密码升级为 BCrypt 强哈希，旧 SHA1 用户平滑迁移。

### 4.11 系统管理

- **系统参数**：
  - 同步数据 / 系统日志 / 日志文件过期时间（天）
  - 是否记录同步成功 / 失败数据、失败日志最大长度、是否记录全量数据
  - 是否启用水印及水印内容
  - 表执行器上限数、是否打印 trace、是否开放 OpenAPI
- **安全配置**：
  - RSA 密钥生成（可指定密钥长度）
  - OpenAPI API 密钥生成（一次性明文返回，安全保存）
  - JWT 密钥管理
  - IP 白名单（`IpWhitelistConfig`）

### 4.12 OpenAPI 集成

- **统一网关**：`/openapi/v1/**` 自动转发至内部 `connector/**`、`mapping/**`、`tableGroup/**` 等接口，外部系统零门槛集成。
- **认证流程**：`/openapi/auth/login`（API 密钥换取 JWT Token，有效期 7200s）→ `/openapi/auth/refresh` 刷新。
- **安全防护**：IP 白名单校验、JWT 校验、时间戳 + nonce 防重放、RSA 加解密（支持公网/内网场景）、请求参数解密。

---

## 5. 支持的连接器

| 连接器 | 数据源 | 目标源 | 支持版本（含以下） |
|--------|:---:|:---:|-------------------|
| ClickHouse | ✔ | ✔ | 22.x+ |
| Dameng（达梦） | ✔ | ✔ | DM8 |
| Doris | ✔ | ✔ | 1.2.x+ |
| DuckDB | ✔ | ✔ | 1.5.x |
| Elasticsearch | ✔ | ✔ | 6.0.0 ~ 8.15.3 |
| File | ✔ | ✔ | *.txt, *.unl |
| H2 | ✔ | ✔ | 2.2.x |
| Http | ✔ | ✔ | HTTP/1.1 |
| Kafka | ✔ | ✔ | 2.10-0.9.0.0+ |
| Kingbase（金仓） | ✔ | ✔ | V8 |
| MongoDB | ✔ | ✔ | 4.2+ |
| MySQL | ✔ | ✔ | 5.7.19+ |
| OceanBase | ✔ | ✔ | 3.x+ |
| OceanBase(Oracle 模式) | ✔ | ✔ | 3.x+ |
| Oracle | ✔ | ✔ | 10g ~ 19c |
| PostgreSQL | ✔ | ✔ | 9.5.25+ |
| Redis | ✔ | ✔ | 源（增量 CDC）5.x~7.x；目标 2.6+ |
| RocketMQ | ✔ | ✔ | 4.9.x+ |
| Sqlite | ✔ | ✔ | 2+ |
| SqlServer | ✔ | ✔ | 2008+ |
| StarRocks | ✔ | ✔ | 2.5+ |
| TiDB | ✔ | ✔ | 5.x+ |
| SQL | ✔ | | 支持以上关系型数据库 |

> 连接器通过 SPI 机制注册，支持扩展新数据源类型。

---

## 6. 技术架构

| 层次 | 说明 |
|------|------|
| 后端 | Spring Boot 2.5 + Maven 多模块工程 |
| 前端 | Thymeleaf 服务端渲染 + Tailwind CSS + FontAwesome |
| 存储 | 内置 H2 数据库（文件模式，`DBDataSync_` 前缀表，开箱即用） |
| 调度 | 内置任务调度器（`ManagerFactory` / `AbstractPuller`），cron / interval 策略 |
| 引擎 | FullPuller（全量：分页游标 + 断点续传 + 表级并发）；IncrementPuller（增量：LOG 解析 / TIMING 轮询 + 自动恢复重试）；FullIncrementPuller（全量+增量：位点捕获无缝衔接） |
| 并发 | 执行模式双轨：SYNC 串行（强一致）/ ASYNC 并发写（高吞吐）；全量表级并发控制（threadNum） |
| 扩展 | SPI 连接器机制 + 插件热加载 + OpenAPI 开放网关 |
| 安全 | RSA 加密、JWT、IP 白名单、防重放（timestamp + nonce）、BCrypt 密码 |

**模块划分**：

```text
DBDataSync
├── dbdatasync-web          # Web 层：控制器、前端页面、OpenAPI 网关
├── dbdatasync-biz          # 业务逻辑层：各领域服务实现
├── dbdatasync-common       # 通用工具、常量、公共类
├── dbdatasync-connector    # 连接器集合（20+ 数据源，SPI 注册）
├── dbdatasync-manager      # 调度管理：同步引擎、任务调度、续跑恢复
├── dbdatasync-parser       # SQL/配置解析、Picker 字段选择
├── dbdatasync-plugin       # 插件机制（自定义同步转换）
├── dbdatasync-sdk          # SDK：模型定义、存储接口、SPI 扩展点
└── dbdatasync-storage      # 存储层：内置 H2 持久化
```

---

## 7. 部署与安装

### 7.1 环境要求

- JDK 1.8+
- 内存建议 2GB+（大型同步任务建议 4GB+）
- 浏览器：Chrome / Edge / Firefox 现代版本

### 7.2 方式一：源码打 ZIP 包（推荐，离线可重复）

仓库内已内置一键打包脚本与 Maven Assembly 描述符（`dbdatasync-web/src/main/assembly/assembly-zip.xml`），产物为**可直接解压运行的 ZIP 部署包**：

```bash
# Windows：仓库根目录执行（需先装好 JDK 1.8+ 与 Maven 3.6+ 并配好 PATH）
build.cmd

# Linux / macOS
./build.sh
```

执行过程：

1. `mvn clean package -Dmaven.test.skip=true`（全模块编译打包，约 3–6 分钟）；
2. Web 模块 Assembly 自动打出部署包并移动到仓库根目录：**`DBDataSync-2.1.4-bin.zip`**；
3. ZIP 目录结构（解压即用）：

```text
DBDataSync-2.1.4-bin/
├── bin/          # startup.bat（Windows）/ startup.sh（Linux）/ stop.sh / startup-docker.sh
├── lib/          # 全部依赖 jar（含各数据库 JDBC 驱动，现场无需联网）
├── conf/         # application.properties（可改端口/存储配置）、dbdatasync.p12
├── plugins/      # 演示转换插件
└── data/         # 首次启动自动创建（内置 H2）
```

> 手动等价命令（不用脚本时）：
> ```bash
> mvn clean package -Dmaven.test.skip=true
> # 产物位于 dbdatasync-web/target/DBDataSync-{version}-bin.zip
> ```

> 也可将代码导入 IDE 直接开发调试；如需放入 `bin/` 同级的自检 `jre8`，把 JDK1.8 的 JRE 目录拷贝为 `DBDataSync-2.1.4-bin/jre8` 即可，`startup.bat` 会自动识别（成功打印 `Using embedded JRE8`）。

4. 解压后启动与应用访问：
   - Windows：双击/执行 `bin\startup.bat`（控制台显示启动日志；`bin\stop` 时直接关窗口或结束 java 进程）；
   - Linux：执行 `bin/startup.sh`（后台启动，`bin/stop.sh` 停止）；
   - 浏览器访问：http://127.0.0.1:18686 ，默认账号密码 **admin / admin**。

### 7.3 方式二：Docker

```shell
docker run -d \
  --name=DBDataSync \
  --restart=unless-stopped \
  -p 18686:18686 \
  -e TZ="Asia/Shanghai" \
  -m 5g \
  --memory-swap=5g \
  -v /opt/DBDataSync/data:/app/DBDataSync/data \
  -v /opt/DBDataSync/logs:/app/DBDataSync/logs \
  -v /opt/DBDataSync/plugins:/app/DBDataSync/plugins \
  <镜像仓库地址>/DBDataSync:latest
```

### 7.4 方式三：源码开发调试

```bash
# 环境与一键编译打包见 §7.2；此处为源码开发调试步骤
$ git clone https://github.com/Zhh9126/DBSYNC.git
$ cd DBSYNC
$ ./build.sh                    # 产 DBDataSync-{version}-bin.zip
```

开发环境源码启动：

```bash
$ cd DBDataSync/dbdatasync-web
$ mvn spring-boot:run
```

> 提示：修改 `dbdatasync-*` 子模块源码后，需先 `mvn install`（建议 `clean install`）更新本地仓库再启动 Web 模块，避免加载旧 class。

---

## 8. 快速开始

以「MySQL → SQLite」全量同步为例：

1. **登录系统**：访问 http://127.0.0.1:18686，使用 admin/admin 登录。
2. **创建连接**：进入「连接管理」，分别新增 MySQL（源）与 SQLite（目标）连接器，保存并「测试连接」。
3. **新建映射**：进入「数据同步 → 新增」，选择源/目标连接器与库，配置同步方式（全量/增量/全量+增量）与执行模式（同步/异步）。
4. **选择表**：刷新表列表，添加源表与目标表，生成字段映射。
5. **配置增强**（可选）：添加过滤条件、字段转换规则、挂载转换插件、配置定时调度。
6. **启动同步**：点击「启动」，在监控页查看同步进度、TPS 与失败记录；如有失败数据可手动重试。

---

## 9. 版本升级说明

- 旧版本（dbsyncer）升级到 DBDataSync 后，启动时自动将内置 H2 数据库中 `dbsyncer_` 前缀的系统表数据迁移至新前缀，历史同步配置、任务与日志无缝保留。
- 项目代码、接口、包名已统一由 `dbsyncer` 重构为 `dbdatasync`，请勿混用新旧依赖。
- 升级前请备份 `data` 目录（H2 数据文件），并核对系统配置（含安全密钥）。

---

## 10. 启动前预检测（PreCheck，产品级能力）

同步任务启动前可对「表级同步（mapping）」与「整库同步（database-sync）」执行预检测：存在 ERROR 级问题时拒绝启动，WARN 级只提示不阻断。

| 规则码 | 级别 | 说明 |
|---|---|---|
| PC101 | ERROR | 字段类型不兼容（如 CLOB→INT、BLOB→VARCHAR） |
| PC102 | WARN | 有损/需转换的类型（如 TIMESTAMP→DATE、带小数 DECIMAL→INT），建议配置内置转换函数 |
| PC103 | WARN | 目标字段长度不足（字符串截断风险） |
| PC104 | ERROR | 增量同步缺主键（UPDATE/DELETE 无法按主键定位行，UPSERT 失效） |
| PC105 | WARN | 字段名命中目标库保留字 |
| PC107 | ERROR | 表组未配置字段映射（整库同步会按同名字段自动推断映射做检查，无法推断时降级为 WARN） |
| PC108 | WARN | varchar 字节/字符语义差异（Oracle/达梦按字节计数） |

- 前端入口：任务列表「预检测」按钮 + 结果弹窗（全局函数 `runPreCheck`，`/js/framework.js`；列表页 mapping 与 database-sync 均已接入）。
- 后端接口：`POST /mapping/precheck`、`POST /database-sync/precheck`；任务启动时强制校验（ERROR 拦截）。
- 复检闭环：预检测会现场刷新目标端表元数据，修复目标 DDL 后无需重启即可复检。

## 11. CDC 增量同步环境前置要求（实测基线）

| 源库 | CDC 机制 | 环境前置要求 |
|---|---|---|
| MySQL | binlog ROW | `binlog_format=ROW`、`binlog_row_image=FULL`、账号具备 REPLICATION SLAVE/CLIENT |
| PostgreSQL | 逻辑复制槽（pgoutput/wal2json） | `wal_level=logical` + REPLICATION 权限账号 |
| Kingbase ES V9 | syslogical_output（pgoutput 协议） | `wal_level=logical`（需重启数据库）+ 连接器扩展参数 `pluginName=syslogical_output` |
| Oracle | LogMiner | 归档模式 + 表级补充日志 + LogMiner 权限（EXECUTE_CATALOG_ROLE 等） |
| 达梦 DM8 | LogMiner（DM 兼容实现） | `ARCH_MODE=Y` 归档 + 归档日志覆盖水位区间。产品已内置达梦实时性机制（无需人工干预）：每轮轮询重建 mining 会话读取最新归档；空轮询且有 SCN 堆积时自动 `ALTER SYSTEM SWITCH LOGFILE` 使未归档提交尽快入档（限速切换，空闲库不切） |

> 达梦实测要点：V$LOGMNR_CONTENTS 只覆盖会话启动时已加入归档文件的存量内容（会话启动后的提交需等归档且重开会话才可见——产品已按每轮重开会话 + 自动切归档修复，实测 CDC 延迟 ≈6s）；V$LOGMNR_CONTENTS 中 COMMIT 行先于同事务 DML 行返回（按提交驱动转发需 ORDER BY SCN 语义，产品已内置）；redo SQL 携带 ROWID 谓词（产品已统一剥离，避免目标端 0 行命中）；DM 无 NO_ROWID_IN_STMT 等效启动项。

## 12. 运行诊断开关

JVM 参数 `-Ddbdatasync.logminer.rowdebug=true` 开启行级诊断日志（默认关闭），输出到 `app.log`：

- `[logminer-rowdebug]`：LogMiner 每行 scn/操作类型/表归属/事务 ID（CDC 断点定位必备）；
- `[cdc-delete-debug]`：DELETE redo SQL 解析后的行数据（Oracle/达梦连接器）；
- `[dm-writer-debug]` / `[writer-debug]`：目标端实际执行 SQL 与受影响行数。

`start_app.cmd` 默认不开启，需要时按脚本内注释启用并重启。

## 13. 离线部署合规说明

- 前端静态资源全部本地打包于 `dbdatasync-web`（`/js/...`、`/img/...`、内置 thymeleaf 模板），**无 CDN/公网字体/公网 JS 依赖**，纯内网可运行（已做无外链扫描核验）。
- 持久化使用内嵌 H2（`data` 目录），无外部中间件依赖；升级/迁移前备份 `data` 目录即可。
- 达梦/金仓/Oracle 等数据库 JDBC 驱动随连接器模块随包分发（如 `kingbase8-8.6.1`），现场无需联网拉取依赖。
- 启动脚本 `start_app.cmd`（Windows）以本地 Maven + JDK8 运行；交付环境建议预置离线 Maven 私服或预先 `mvn install` 产出部署物。

## 14. 2026-10-18 更新摘要（本阶段交付）

- **S1–S5 同构库 CDC 矩阵全部实测 PASS**：MySQL→MySQL、PG→PG、Oracle→Oracle、达梦→达梦、金仓→金仓，全量 + 增量 insert/update/delete 端到端延迟 6–9s（含归档/轮询周期）。
- 达梦 LogMiner 兼容修复：ORDER BY SCN（事务转发语义）、TIMESTAMP/DATE 字面量解析、ROWID 谓词剥离；实时性修复（每轮重开会话 + 自动切归档 + 保守 SCN 推进），实测增量延迟 ≈6s 且不丢未归档提交。
- 金仓 V9 CDC 支持：`syslogical_output` 插件解码（`KingbaseSyslogicalOutputDecoder`）。
- 预检测全量回归通过；整库任务字段映射误报修复（PC107 自动按同名字段推断映射做类型检查）。
- 前端预检测 UI 全链路验证通过（列表按钮 + 通过/阻断两类结果弹窗）。
- **字段映射自动映射增强**：添加表映射自动按同名（忽略大小写）生成全部字段映射（主键自动识别）；字段映射页打开时若映射为空自动补齐，并列出未匹配字段提示条由用户手动修正；「同名映射」按钮忽略大小写。
- **新菜单「系统管理 → 操作手册」**：内置 14 章详细离线用户手册（连接参数、数据同步全流程、预检测、记录导出、整库迁移、增量前置条件、诊断开关、FAQ、离线部署）。
- **数据同步记录修复**：时间列由原始毫秒时间戳格式化为 `yyyy-MM-dd HH:mm:ss`（前端列表 + CSV 导出开始/结束时间），耗时列正确计算。
- **演示任务与记录保留交付**：S1_mysql2mysql_demo / S4_dm2dm_demo / S5_kb2kb_demo（全量+增量实测通过并保留记录）、整库 T4_pg2dm_wholeDB_demo（已完成）；「数据同步/数据同步记录/整库迁移」三页均有真实记录（详见 TEST_REPORT §12）。
- 测试记录与修复明细见 `E:\DBSYNC\reports\TEST_REPORT.md` 与 `E:\DBSYNC\reports\OPTIMIZATION_REPORT.md`。

---

## 15. 2026-10-19 更新摘要（整库迁移 A/B/C/D 产品化）

本轮以「对标 SeaTunnel 收费版（商业闭源）能力、源码级固化」为目标交付四项整库迁移能力，均经真机 E2E 验证：

- **A 自动建库建表**：目标库缺失时 `Runner` 先 `ensureTargetDatabase`（`CREATE DATABASE`）再读元数据，杜绝 Druid 池静默回退 URL 默认库；目标表缺失时 `autoCreateTargetTable` 按源端 DDL 建表（`MySQLConnector.getTargetTableDDL` 兼容完整 `CREATE` 语句与表名前缀重排）。实测：整库任务 A1 一键迁移 `dbsync_src → dbsync_autodemo`（库与表均自动创建），先全量落库，再一键生成增量驱动接续 binlog，源端 INSERT 5 秒内同步至目标。
- **B 一键接续增量**：`POST /database-sync/upgrade?autoStart=` 一键把已完成整库任务升级为「增量驱动」，自动创建同库映射 Mapping（`increment` + LOG 监听）并回写 `mappingIds`；幂等（重复点击提示「该任务已创建接续驱动」）。UI：整库列表「接续增量」按钮仅在任务完成态显示。
- **C 实例级一键全库映射**：`POST /database-sync/expandDatabases` 枚举源实例全部业务库（内置 MySQL/Oracle/PG/达梦/金仓系统库过滤 + 排除与已选去重），UI「一键映射全部库」批量生成库映射卡片。
- **D 新表自动发现**：任务新增 `enableAutoDiscover` 开关（迁移策略区复选框），运行时扫描源端各库表清单按簇比对既有表映射，差集为新表 → 自动建目标表 + 追加表映射 + 参与本轮迁移（视图跳过、单簇扫描失败不阻断）。实测 D1 任务：既有 `s1_emp`（9 行）+ 自动发现 `t_user`（1000 行）、`usr_info`（52 行）、`s99_discover_demo`（3 行）全部同步一致、0 失败。
- **UI 优化**：列表分页统一 10 行/页、去底部大留白；同步记录页新增序号列；仪表盘统计卡支持下钻。
- 全量任务/记录清单与实测数据见 `E:\DBSYNC\reports\TEST_REPORT_WAVE2.md`；修复明细见 `E:\DBSYNC\reports\OPTIMIZATION_REPORT_WAVE2.md`。

---

## 16. 2026-10-25 更新摘要（深度测试 + 能力对标固化）

本轮以「真实环境全流程实测 + 对标商业产品（NineData 等）」为目标，全部交付经真机验证：

- **深度 BUG 修复 15+ 项**（单元测试 17 用例先行复现后转绿）：只读 SQL 校验绕过（CTE 写语句/INTO OUTFILE）、分片 UTF-8 字符边界、binlog 短字节数组越界、binlog 字符串跨平台乱码（中文 Windows GBK）、BIGINT UNSIGNED 翻负（BigInteger）、DATETIME(6) 微秒丢失、Channel 契约与限速死循环、SyncEngine 异步异常吞没与顺序死锁、断点游标数值比较与原子落盘、UPSERT 参数重复等。
- **实时同步延迟优化**：缓冲执行器消费周期 300ms→100ms，实测 INSERT 端到端延迟 **平均 173ms、最低 27ms**（此前 1.1s）；反向扫描 N+1 消除（每页一次批量 IN）。
- **CDC 覆盖全景**：实时 CDC（LOG）9 库——MySQL/Oracle/PostgreSQL/Kingbase/SQLServer/达梦/OceanBase/MongoDB/ClickHouse；定时轮询（TIMING）覆盖其余全部连接器（含 TiDB 限定轮询）。
- **Oracle CDC 前置条件预检测（PC120/PC121/PC122）**：归档模式与补充日志检查，ERROR 阻断启动并给出修复 SQL——闭环实测（关补充日志→阻断→恢复→通过）。
- **DDL 风险分级**：`ddlSyncPolicy`（all/add_only/off）系统配置，高风险变更拦截需人工确认；跨源 DDL RENAME 支持（实测 Oracle→MySQL/金仓 10s）。
- **数据对比独立一级菜单 + 模块升级**：全量逐行/抽样两种模式、差异自动生成修复 SQL（BLOB 十六进制字面量）、自动订正默认关闭（对比与订正解耦）、差异详情弹窗展示修复 SQL、反向扫描批量优化、周期对比。
- **整库迁移一键数据对比**：迁移完成后按全部映射一键创建对比任务（实测 diffTotal=0 一致）。
- **跨平台与离线**：平台默认字符集依赖修复 8 处、静态资源大小写修正、Maven 离线编译验证、前端无 CDN/外链审计、静态资源 no-cache 治理。
- **管理能力**：任务数量/运行并发上限（启动阻断实测）、深色/浅色主题切换、操作手册同步更新。
- 实测数据与修复明细见 `reports/TEST_REPORT_WAVE3.md`（环境：Windows 控制台 + MySQL/Oracle/金仓/Redis 真实库）。

---

## 17. 2026-10-26 更新摘要（全量分片并发读）

对标 DataX `splitPk` / CloudCanal `chunk` 的「全量单表分片并发读」能力落地，缩小与业界先进工具在全量迁移吞吐上的差距：

- **全量单表分片并发读（Wave4 核心）**：映射级配置 `fullSplitKey`（分片键）+ `fullSplitCount`（分片数），全量同步时按分片键值域切分为 N 个左闭右开区间，每分片独立游标循环并发执行；并发池 = min(分片数, 执行线程数)。存量映射未配置参数时完全走原串行路径，零影响。
- **值域自动探测**：复用 QUERY_COUNT SQL 把 `COUNT(*)` 替换为 `MIN(x), MAX(x)` 探测分片键值域（方言表名/过滤条件自动沿用）；探测失败/空表/等值表自动降级串行。
- **防注入**：分片键经 `SqlIdentifierValidator` 白名单校验后才拼接进 SQL；范围条件参数化 `?` 绑定，参数顺序与游标参数天然对齐（`[splitStart, splitEnd, cursorArgs..., offset, limit]`）。
- **失败即停**：任一子分片异常 → 中止父任务 → 聚合上抛，其余分片尽力退出，不产生静默丢数。
- **Web UI**：映射编辑/新增页新增「全量分片并发读」配置区（对标 DataX splitPk 标识），写入 params JSON 并回显。
- **单元测试 13 项**全通过（区间切分边界/无缝拼接/空值域/SQL 注入 4 形态/复合主键游标/恶意列名拦截/探测 SQL 形状），全模块 Maven 离线打包通过。
- 设计论证、落点与验证细节见 `reports/OPTIMIZATION_REPORT_WAVE4_FULL_SPLIT.md`。

---

## 18. 2026-11-05 更新摘要（SQL 兼容度扫描器）

针对数据库信创迁移「应用端 SQL 语法兼容」这一核心适配成本，新增**迁移前 SQL 兼容度评估**能力（对标 NineData SQL 转换 / 阿里 DMS / 达梦迁移助手的评估环节，纯离线）：

- **纯离线静态扫描**：输入「源方言 + 目标方言 + SQL 文本（可多条）」，输出分级（error/warn/info）问题清单与改写建议，在连库之前暴露应用适配成本；不做自动转换（避免误导），复杂项给人工重写方向。
- **四条规则族**：函数族（NVL→COALESCE、DECODE→CASE WHEN、GROUP_CONCAT→STRING_AGG、LISTAGG 等，pair 定向）、操作符族（`||`/`:=`/`%`/`::`）、结构族（CONNECT BY/MINUS/START WITH/ROWNUM/LIMIT/ON DUPLICATE KEY/PIVOT）、类型族（VARCHAR2/CLOB/BLOB/NUMBER/BOOLEAN，支持带长度形态）。
- **方言家族豁免**：达梦/OceanBase Oracle 兼容模式支持 ROWNUM/NVL 等时不误报；MySQL 系支持 LIMIT 时不报。
- **多语句 + 降级**：按分号拆分逐条 jsqlparser 解析，单条失败不中断整体（PL/SQL 块给 unparseable 提示）；未知方言降级为「未扫描」提示。
- **支持方言**：MySQL / Oracle / PostgreSQL / Kingbase / DM / OceanBase / SQLServer / ClickHouse / TiDB。
- **接口与页面**：`POST /sql-scan/scan` 扫描接口 + `GET /sql-scan` 独立扫描页（侧边栏菜单入口）。
- **单元测试 12 项**全通过（四向迁移样本 + 同族豁免 + 降级路径 + 防重复命中回归），模块离线编译通过，前端 `node --check` 通过。
- 设计论证与验证细节见 `reports/OPTIMIZATION_REPORT_WAVE5_SQL_SCAN.md`。

---

## 19. 2026-11-05 更新摘要（订正校验增强：快速体检 + 分片并发）

针对迁移后「数据一致性校验闭环」，在既有 validate-sync 订正校验模块上补齐两大能力（对标 SeaTunnel/NineData 校验环节）：

- **快速体检模式（compareMode=health）**：源/目标各执行 `COUNT + MIN/MAX(主键)` 一条 SQL，秒级完成行数与主键值域双维度粗检；不定位行级差异、不自动订正，适合迁移完成后第一时间健康检查。
- **分片并发校验（full 模式增强）**：单列主键 + 配置分片并发数（>1）时，按主键 MIN/MAX 值域切分为左闭右开区间，多线程并发逐行比对（复用 Wave4 `splitHalfOpenRange` 切分；目标端按主键 IN 批量回查天然落在分片区间，无重叠遗漏）。任一子分片失败即取消任务并上抛，其余分片尽力退出。
- **存量零影响**：`fullSplitCount` 默认 0、compareMode 保持 full/sample 语义，不满足分片条件自动降级原串行路径。
- **前端**：新增任务页「对比模式」增加 health 选项 + 「分片并发数」输入（full 模式显示）；编辑页高级设置支持分片数回显。
- **单元测试**：新增 `ValidateSyncExecutorTest` 8 项（模式规范化 / 分片适用判定 / 区间切片边界），回归 `FullSplitLogicTest` 13 项全绿，parser 模块累计 22 项通过；模块离线编译与前端 `node --check` 通过。
- 设计论证与验证细节见 `reports/OPTIMIZATION_REPORT_WAVE6_VALIDATE_SYNC.md`。

---

## 20. 2026-11-05 更新摘要（整库迁移编排阶段状态机）

为整库迁移任务补齐**迁移编排阶段状态机**（方案方向三收敛落地；勘察确认断点续传/表级进度/全量→增量位点/任务重启恢复/一键接续增量/一键数据对比均已具备，本轮只补缺失的「阶段编排可视化」）：

- **阶段链**：`schedule(编排就绪) → full(全量迁移) → increment(增量追平) → validate(一致性校验) → switchover(切换)`，人工显式推进，避免误越级。
- **持久化**：阶段写任务级 `Meta.SNAPSHOT(migrationStage)`，与任务配置解耦（不随编辑/导出污染配置）；读取 SNAPSHOT 优先、回落配置默认。
- **纯逻辑工具**：`MigrationStageUtil`（sdk）提供阶段链/文案/归一化/推进计算，8 项单测覆盖。
- **接口与前端**：`POST /database-sync/advanceStage` + 整库迁移列表新增「迁移阶段」列（徽标 + 推进按钮）。
- **验证**：sdk 9 项测试全绿（新增 7）、biz/web 离线编译通过、前端 `node --check` 通过。
- 设计论证与验证细节见 `reports/OPTIMIZATION_REPORT_WAVE7_MIGRATION_ORCHESTRATION.md`。

---

## 21. 2026-11-05 更新摘要（SQL 兼容扫描器增强：改写草稿）

在 Wave5 扫描器基础上增加**语法转换辅助**（方向二）——从「评估」进到「评估 + 改写草稿」：

- **改写草稿**：对 `warn` 级可机械映射项自动生成改写后的 SQL（绿色展示），如 NVL→COALESCE、SYSDATE→NOW()、TO_CHAR→DATE_FORMAT、IFNULL→COALESCE、ILIKE→LIKE、VARCHAR2→VARCHAR、`::timestamp` 移除等；
- **error 级保留人工**：目标无等价语义（CONNECT BY 递归、LISTAGG 等）草稿留空，标「需人工重写」；
- **保守定位**：草稿仅字符串级替换、供复制确认，不自动落地；复杂项仍给建议由人工写，不破坏「评估不误导」；
- **前端**：sql-scan 表格新增「改写草稿」列（有草稿=绿色 code 块，error=需人工，info=—）。
- **验证**：扫描器测试 12→17 项全绿（新增 5 项草稿用例），biz/web 离线编译通过，前端 `node --check` 通过。
- 设计论证与验证细节见 `reports/OPTIMIZATION_REPORT_WAVE8_SQL_CONVERT_DRAFT.md`。

---

## 22. 2026-11-05 更新摘要（整库迁移性能适配建议）

- **新增**「性能适配建议」：迁移完成后一键生成**文档级**建议报告——
  - 总览：总数据量、净吞吐（行/s）、耗时、表数；
  - 大表 Top10（≥100 万源行）→ 目标库索引 / 分区专项建议；
  - 差异 / 失败表 → 建议补迁或重校验；
  - 读写参数建议（readNum/batchNum/threadNum 与吞吐关系）；
  - 目标库参数建议（按方言：MySQL/Oracle/DM/PG/KB）；
  - 汇总结论（是否可进入校验/切换）。
- **保守定位**：只输出建议，不自动执行任何 DDL / 参数修改；未执行迁移时给提示。
- 入口：整库迁移列表 → 操作列「性能建议」（带宽表按钮）；路径 `/database-sync/page/perf?id=`。
- 验证：biz/web 离线编译通过，前端 JS `node --check` 通过。
- 设计论证与细节见 `reports/OPTIMIZATION_REPORT_WAVE9_PERF_ADVICE.md`。（限速暴露、低峰时间窗调度留作后续，见报告§5）

---

## 23. 2026-11-06 更新摘要（遗留 BUG 修复 + 方向二语法转换补齐）

- **P0 `perfAdvice` 行数口径**：收敛为唯一口径（总数据量 = 成功 + 失败，取自任务级 Meta；吞吐分子只用成功行；大表行数取该表明细 `sourceTotal`），删除只累加前 10 张表的死计算，修正「数据总量与差异均为 0」的矛盾结论；报告页新增成功/失败行数、口径标注与精确数值。
- **P0 整库迁移创建任务 `syntax error : {`**：根因是 fastjson2 `JSON.parseArray` 要求 `databaseMappingsJson` 根为数组，服务端未捕获并把第三方解析器异常原文透传前端。现前后端统一数组契约 + 服务端 6 步兼容归一化，非法输入统一返回可读业务提示。
- **P1 方向二补齐**：① DDL 建表语句类型映射转换草稿（VARCHAR2→VARCHAR、NUMBER→DECIMAL(18,4)、CLOB→TEXT、BOOLEAN→TINYINT(1) 等，error 级不生成、纯字符串处理不执行 DDL）；② 存储过程/函数/触发器/视图定义纳入扫描（文本降级粗粒度提示）；③ 创建页可选应用 SQL 兼容度预检（文本 + 本地目录，独立接口、默认收起、仅提示不阻断）。
- **保守定位**：所有新输出均为草稿/建议，不自动执行 DDL、不连库；存量任务零影响。
- **验证**：全模块离线编译通过；biz 单测 77/77 全绿（新增 47 + QA 边界 13 + 既有扫描器 17 回归）；前端内联 JS `node --check` 通过。
- 设计论证与验证细节见 `reports/WAVE10_INCREMENTAL_DESIGN.md`、`reports/OPTIMIZATION_REPORT_WAVE10_DDL_CONVERT.md`、`reports/TEST_REPORT_WAVE10.md`。

---

## 24. 2026-11-06 更新摘要（迁移策略与成本管控 + 编排补齐）

把《数据库迁移深度优化方案》剩余的方向五、方向三剩余、方向二 P2 收口：

- **方向五 限速暴露**：整库迁移策略新增「每秒最多行数 / 每秒最多字节数」（默认 0 = 不限速），经 `Mapping.params` 传入主链路，在批次级按令牌桶语义平滑限流；`dbdatasync-engine` 的 `Channel` 是孤立副本、运行时未接入，故不引入该模块依赖，也不改动任何 `pom.xml`。
- **方向五 低峰时间窗**：新增「仅在低峰时间窗内启动」开关与起止时刻（支持跨午夜），窗口外启动自动预约到窗口开始时刻（复用既有 `ScheduledTaskService`）；开关默认关闭、预约失败降级立即启动，服务重启后预约失效需重新触发。
- **方向五 成本统计**：性能建议报告新增限速/时间窗配置回显、每千行平均耗时、按限速估算的理论最短耗时与成本摘要，全部为**纯派生**，不改 Wave10 行数口径、不写 Meta；分片并发读补充耗时/分片数/平均耗时统计日志。
- **方向三 阶段自动推进**：新增「全量完成后自动推进到增量追平」开关（默认关闭），仅在任务成功收尾、且当前阶段为 `full` 时推进一次；人工推进按钮与末位不越界语义不变。**回滚预案按方案要求落为文档级**：`reports/MIGRATION_SWITCHOVER_ROLLBACK_PLAN.md`（切换检查清单、切换流程、回退触发条件、数据回退策略、演练模板），系统不自动切流/回切。
- **方向二 P2-1 自定义规则文件**：支持通过 `-Ddbdatasync.sqlscan.custom.rules=<classpath 资源>` 加载外部 `.properties`/`.json` 规则并与预置规则合并；未配置或文件非法时降级为仅预置规则，扫描结果与 Wave5/Wave8 完全一致。示例见 `dbdatasync-biz/src/main/resources/sqlscan-custom-rules.example.properties`。
- **QA 观察项补齐**：SQL 预检目录输入的「单文件超 1MB 被跳过」「目录内无 *.sql」两类场景补充可读提示。
- 验证与落地清单见 `reports/OPTIMIZATION_REPORT_WAVE11_STRATEGY_COST.md`、`reports/TEST_REPORT_WAVE11.md`。

---

## 25. 2026-11-07 更新摘要（方向一收口：对账报告导出 + 差异条目一键重推）

把《数据库迁移深度优化方案》方向一 P0 进度表里最后两项收口：

- **对账报告导出（CSV）**：校验结果详情页新增「导出对账报告」，差异详情弹窗新增「导出本条明细」。全任务导出按当前状态筛选遍历全部分页，单明细导出只取该行；CSV 含报告抬头汇总（任务 ID / 生成时间 / 明细行数 / 差异总数 / 已订正总数 / 导出差异条目数 / 完整性判定）、17 列字段级差异明细（含主键标识、源值/目标值、修复 SQL、订正状态）与末尾截断说明。UTF-8 BOM，Excel 打开中文不乱码；单元格做 CSV 公式注入防护与标准转义。导出接口为**只读**。
- **差异条目一键重推**：差异弹窗内每条「未订正的行数据差异」提供「重推」按钮，二次确认后只重推该下标对应的 **1 条**，复用既有 `ValidateSyncExecutor.repairContent`；删除类差异（目标多余）单独给出不可恢复警告。`fixedTotal` 仅在真实订正成功时 +1 且封顶 `diffTotal`，不做虚报；下标越界 / 已订正 / 非行数据差异返回可读提示且不写库。既有「手动订正(最多100条)」整行入口完全不变。
- **完整性如实标注**：受既有 `MAX_CONTENT_ITEMS = 200` 与 `DATA` 列 65535 字节上限约束，报告显式标注「可能不完整」及原因，避免把导出内容误当作全部差异；本轮**不改存储模型**，只如实告知。
- **零影响与保守定位**：全部为新增接口 / 新增方法 / `default` SPI 方法 / 新增按钮，不改既有方法签名、校验主逻辑与既有页面控件；不新增第三方依赖、不改 `pom.xml`；导出不执行任何业务 SQL，重推沿用既有 INSERT/UPDATE/DELETE 语义、不自动执行 DDL。
- **验证**：全模块离线 `clean install` **34/34 SUCCESS**；四模块单测 **230/230**（sdk 18 / parser 52 / biz 131 / web 29，含本轮新增 43 项）；前端 2 处 `node --check` 通过；13 文件 UTF-8 无 BOM。另独立修复既有 `ExpiringCacheTest.testAutoCleanup` 的时间参数与断言矛盾（**未改产品代码**），使全量回归无红灯。
- 需求、设计与验证细节见 `reports/WAVE12_INCREMENTAL_PRD.md`、`reports/WAVE12_INCREMENTAL_DESIGN.md`、`reports/OPTIMIZATION_REPORT_WAVE12_RECONCILIATION.md`、`reports/TEST_REPORT_WAVE12.md`。

---

## 26. 2026-11-07 更新摘要（Wave13：字段级指纹校验 + DDL 变更辅助）

把外部文章《开源 CDC 的 DDL 黑洞与 KFS 的破局解法》点名的两大痛点映射回设计文档欠项并补齐：

- **A 线 字段级指纹校验（compareMode=checksum）**：`validate-sync` 校验引擎新增分块 MD5 指纹比对模式，纯计算定位、不订正。新增 `RowFingerprintUtil`（byte[]→hex、数值 `1`/`1.0` 等价、日期 ISO 归一等，消除跨库假差异/假一致）；`fullCompareMode` 白名单追加 checksum（非法回退 full，防静默降级）；`checkRowData` 分流（checksum 不进分片并发）；块不一致产出 `type=rowData` 差异项（msg 含块主键范围 + 双摘要前缀），`fixed` 恒 0。前端 add/editSetting 增加选项与回显（修正了 `th:value` 在 `<select>` 上不生效的缺陷）。复用既有 `pageRows`/`commonColumnNames`/`buildKey`/`normalize`，Wave12 三条链路（manualRevise/reviseDiffItem/exportReport）零改动即兼容。
- **B 线 DDL 变更辅助（ALTER TABLE 拆解）**：新增独立离线工具，输入源端 `ALTER TABLE` → 正则结构化拆解为 ADD/MODIFY/CHANGE/DROP/RENAME 单元 → 复用 `DdlTypeMapping`/`SqlCompatibilityRules` 生成目标端 DDL 草稿 + 风险标注 + 兼容度提示。**不连库、不执行 DDL、不触达运行时 DDL 链路**（与既有 `GeneralBufferActuator`/`parser/ddl/` 完全独立）。新增 `POST /sql-scan/ddlChangeAssist` 接口与 sql-scan 页面入口。
- **保守定位与零影响**：默认 `full`，存量任务零变化；不改 DAO/存储模型、`MAX_CONTENT_ITEMS=200` 与 65535 字节约束不变；CSV 报告按 Wave12 机制标注「可能不完整」；不改 `pom.xml`、不新增依赖。
- **明确不做**：运行时 DDL 自动执行、AST 级 PL/SQL 语义解析、KUFL/物理日志直读、checksum 自动订正/块内下钻（下钻复用 full 模式）。
- **验证**：全模块 `clean install` **34/34 SUCCESS**；四模块单测 **255/255**（sdk 18 / parser 65 / biz 143 / web 29，新增 25、既有 230 基线不退化）；前端 2 处 `node --check` 通过；14 文件 UTF-8 无 BOM。
- 需求、设计与验证细节见 `reports/WAVE13_INCREMENTAL_PRD.md`、`reports/WAVE13_INCREMENTAL_DESIGN.md`、`reports/OPTIMIZATION_REPORT_WAVE13_CHECKSUM_DDL.md`、`reports/TEST_REPORT_WAVE13.md`。

---

## 27. 2026-11-07 更新摘要（Wave14：差异条目批量勾选重推）

继续推进方向一 P0 收口延伸里「仍待立项」的一项——差异条目批量勾选重推（Wave12 只做了逐条重推，差异多时操作繁琐）：

- **批量入口**：差异详情弹窗表格新增选择列（仅 `rowData` 可重推行有勾选框）+ 表头「全选」+「批量重推(N)」按钮（N=已选数量，0 选时禁用，随勾选实时刷新）。
- **后端 `reviseDiffItems`**：选择/校验勾选下标（越界/已订正/非行数据如实计入 `failures`）→ **一次性**复用既有 `repairContent`（INSERT/UPDATE/DELETE 语义不变）→ 遍历每条 item 被就地标记的 msg 判定成功/失败 → `fixedTotal` 经 `nextFixedTotal` 仅在真实成功时累加（不虚报、封顶 `diffTotal`）。返回 `successCount`/`failureCount`/`total`/`failures[]`/`detail`，其中 `successCount+failureCount==total` 恒成立。
- **安全定位**：删除类差异（目标多余）批量重推前走危险二次确认（`confirmType=danger`，提示删除不可恢复）；单次上限 100（与 `repairContent` 内部封顶一致）；跨明细聚合、存储模型改造均不做。
- **零影响与保守定位**：完全复用 Wave12 契约——不改存储模型/DAO、`MAX_CONTENT_ITEMS=200` 与 65535 字节约束不变、不新增依赖、不改 `pom.xml`；既有的逐条「重推」按钮与「手动订正(最多100条)」整行入口完全保留。
- **验证**：全模块 `clean install` **34/34 SUCCESS**；四模块单测 **264/264**（sdk 18 / parser 74 / biz 143 / web 29，新增 9、既有 255 基线不退化）；前端 `node --check` 通过；8 文件 UTF-8 无 BOM。
- 需求、设计与验证细节见 `reports/WAVE14_INCREMENTAL_PRD.md`、`reports/WAVE14_INCREMENTAL_DESIGN.md`、`reports/OPTIMIZATION_REPORT_WAVE14.md`、`reports/TEST_REPORT_WAVE14.md`。

---

## 28. 2026-11-07 更新摘要（Wave15：DDL 变更辅助扩展——复合 ALTER 子句识别）

继续推进方向二「语法兼容升级」中 Wave13 B 线 DDL 变更辅助的已知限制项——复合/约束/索引/主键/默认值类 ALTER 此前一律降级为 `UNKNOWN`，等于把高频、可安全识别的结构抹平成「无法解析」。

- **识别扩展**：`DdlChangeSplitter` 新增 7 类复合子句识别（严格排在通用 ADD/DROP 列之前，避免误判）：`ADD CONSTRAINT <名> (PK|FK|UNIQUE|CHECK)`、`ADD [UNIQUE] INDEX <名>`、`ADD KEY <名>`（MySQL）、`ADD (PK|FK|UNIQUE)` 无名约束、`ALTER COLUMN <列> SET/DROP DEFAULT`、`DROP CONSTRAINT|PRIMARY KEY|INDEX|FOREIGN KEY|KEY <名>`。识别为明确单元（`ADD_CONSTRAINT`/`ADD_INDEX`/`DROP_CONSTRAINT`/`DROP_PRIMARY_KEY`/`DROP_INDEX`/`SET_DEFAULT`/`DROP_DEFAULT`），不再 `UNKNOWN`。
- **新字段**：`DdlChangeUnit` 新增 `objectName`（约束/索引/主键标识名），纯增量、缺省空串、序列化向后兼容；前端 `sql-scan/index.html` 变更单元表新增「对象」列渲染 `objectName`。
- **保守定位**：复合单元 `level=info`、`sourceType/targetType` 空（不做类型映射），因此 `DdlChangeAssistService` 的 `rebuild` 原样保留整条语句、`changed=false` 分支返回原 SQL（不自动改写/不自动执行）；分区表 `ADD/DROP PARTITION`、约束体内部列清单语义、AST 级解析均维持 `UNKNOWN` 降级。不改存储模型、不引 jsqlparser、不连库、不触达运行时 DDL 链路。
- **零影响**：`DdlChangeAssistService`/`DdlChangeDraft` 无需改动（扩展内聚在 Splitter+Unit）；既有列操作单元 `objectName` 留空，前端视觉无副作用。
- **验证**：全模块 `clean install` **34/34 SUCCESS**；四模块单测 **156/156**（biz 模块 `DdlChangeAssistTest` 增至 25 用例，新增 13 覆盖复合子句、既有 12 全绿不退化）；前端内联 JS `vm.Script` 语法校验通过；4 文件 UTF-8 无 BOM。
- 需求、设计与验证细节见 `reports/WAVE15_INCREMENTAL_PRD.md`、`reports/WAVE15_INCREMENTAL_DESIGN.md`、`reports/OPTIMIZATION_REPORT_WAVE15.md`、`reports/TEST_REPORT_WAVE15.md`。

---

## 29. 2026-11-07 更新摘要（Wave16：DDL 变更辅助扩展——分区表 PARTITION 子句识别）

延续 Wave13 B 线 DDL 变更辅助与 Wave15 复合 ALTER 子句识别，把分区表高频维护子句从 `UNKNOWN` 降级中识别出来——此前 `ADD / DROP / TRUNCATE / ATTACH / DETACH PARTITION` 一律被抹平成「无法解析」，影响扫描识别率与人工二次判断。

- **识别扩展**：`DdlChangeSplitter` 新增 5 个分区正则（`P_ADD_PARTITION` / `P_DROP_PARTITION` / `P_TRUNCATE_PARTITION` / `P_ATTACH_PARTITION` / `P_DETACH_PARTITION`），识别为明确单元（`ADD_PARTITION` / `DROP_PARTITION` / `TRUNCATE_PARTITION` / `ATTACH_PARTITION` / `DETACH_PARTITION`），不再 `UNKNOWN`；`ADD` / `DROP` 两类兼容可选 `IF NOT EXISTS` / `IF EXISTS` 修饰。
- **优先级防护**：5 个分区正则严格排在通用 `P_ADD` / `P_DROP` 列之前，杜绝 `ADD PARTITION p1` 误判为「列 ADD（列名 PARTITION）」、`DROP PARTITION p1` 误判为「列 DROP（列名 PARTITION）」。
- **复用字段**：分区名写入已有 `objectName` 字段（Wave15 已加，缺省空串、序列化向后兼容）；前端 `sql-scan/index.html` 变更单元表已有「对象」列渲染，分区名自动可见，**前端无需改动**。
- **保守定位**：分区单元 `level=info`、`sourceType/targetType` 留空，`DdlChangeAssistService.rebuild` 原样保留整条语句、`changed=false` 返回原 SQL（不自动改写 / 不自动执行）；分区定义内部体（`VALUES LESS THAN (...)` 等）不解析；不引 jsqlparser、不连库、不触达运行时 DDL 链路。`DdlChangeAssistService` / `DdlChangeDraft` / `DdlChangeUnit` 零改动。
- **验证**：全模块 `clean install` **34/34 SUCCESS**；biz 模块 `DdlChangeAssistTest` 增至 **36 用例**（新增 11 覆盖 5 类分区、IF EXISTS/NOT EXISTS、优先级防误判、返回原 SQL、多语句互不误判），全绿；2 改动文件 UTF-8 无 BOM；源码无 JDBC / 运行时 DDL 链路（静态检查不退化）。
- 需求、设计与验证细节见 `reports/WAVE16_INCREMENTAL_PRD.md`、`reports/WAVE16_INCREMENTAL_DESIGN.md`、`reports/OPTIMIZATION_REPORT_WAVE16.md`、`reports/TEST_REPORT_WAVE16.md`。

---

## 30. 2026-11-07 更新摘要（Wave17：剩余待办全量落地——六模块 + 真机 E2E 套件）

对分诊中剩余 7 项待办全部给出真实、可编译、可单测的代码交付（「规划的都要实现」），并诚实标注每项的运行时验证边界：

- **PL/SQL/存储过程识别**（sqlscan 扩展，SQL-P01~P06）：识别 `CREATE [OR REPLACE] PROCEDURE/FUNCTION/TRIGGER/PACKAGE/PACKAGE BODY/TYPE[ BODY] <名>`，捕获对象名，error 级、不自动改写；纯正则、与既有扫描零冲突。
- **慢 SQL 采集**（新包 `slowsql`）：`SlowSqlRecord/Threshold/Collector` SPI + 线程安全环形缓冲 `RingBufferSlowSqlStore`（满覆盖最旧）+ `SlowSqlAnalyzer`（avg/max/**p95 索引法**/topN）；引擎/驱动挂钩为运行时接线点。
- **逻辑导入导出**（新包 `logicalio`）：RFC4180 CSV 导出/导入（引号字段、"" 转义、引号内换行、CRLF、首行表头映射、列数校验）+ `InsertSqlExporter`（mysql/oracle/pg 三方言标识符引用、单引号转义、批量多值/INSERT ALL）；特殊字符 round-trip 无损。
- **回滚预案/自动切流/影子写**（新包 `cutover`）：10 相位 `CutoverStateMachine`（合法迁移表 + 回滚链 + ROLLED_BACK→重走增量）、`ShadowWriteBuffer`（配对/失配计数/flipSafe 判定）、`RollbackPlanner`（SWITCHED/SWITCHING/SHADOW_WRITING 三相位预案）。
- **跨明细聚合批量重推**（新包 `repush`）：按业务键聚合（保序/空键独立）→ 分批（校验/保序）→ 执行编排（失败批记录、abortOnFailure 中止语义）+ `RepushExecutor` SPI。
- **存储模型改造（非破坏式）**（新包 `storage`）：不解既有约束，提供外置分块层——`ContentChunker`（UTF-8 字节边界/代理对安全 + sha256 校验头）+ `ChunkAssembler` + `OversizedContentOffloader`（超 65535 字节内容外置为 `DBDS-REF` 引用串）；接入既有存储层属后续专项。
- **真机 E2E 套件**：`tools/e2e/RealDbE2eHarness.java`（JDBC 前置检查：Oracle 归档/补充日志、MySQL binlog ROW/FULL、金仓 wal_level/复制角色/**BYTEA 往返冒烟**、达梦版本/归档）+ `reports/E2E_REAL_DB_RUNBOOK.md`；本沙箱无真实库未执行，真机环境可运行。
- **验证**：全模块 `clean install` **34/34 SUCCESS**；biz 模块单测 **221/221**（新增 54：cutover 8/logicalio 16/repush 12/slowsql 6/storage 8/scanner +4）；新文件 UTF-8 无 BOM；集成期 10 个首轮失败全部定位修复（留痕见 `reports/TEST_REPORT_WAVE17.md` §3）。
- 细节见 `reports/OPTIMIZATION_REPORT_WAVE17.md`、`reports/TEST_REPORT_WAVE17.md`、`reports/E2E_REAL_DB_RUNBOOK.md`。

---

## 31. 2026-11-07 更新摘要（Wave18：Wave17 模块运行时接线——服务层 + REST 端点）

把 Wave17 交付的五个纯逻辑模块全部接入真实运行链路（产品功能化）：

- **慢 SQL 采集**：`SlowSqlService`（biz Bean）+ `SlowSqlController`——`POST /slow-sql/collect`（引擎/驱动挂钩点）、`/report`（avg/max/p95/topN）、`/records`、`/config`（阈值/容量/采样率运行时调整）。
- **逻辑导入导出**：`LogicalIoController`——`POST /logical-io/exportCsv`、`/exportInsert`（mysql/oracle/pg）、`/importCsv`；纯转换端点，库端 IO 由连接器完成。
- **切流/影子写/回滚**：`CutoverManagerService`（按 taskId 会话）+ `CutoverController`——`POST /cutover/create`、`/transition`、`/state`、`/shadow/record`、`/shadow/flipSafe`、`/rollbackPlan`、`/remove`；状态权威+安全判定，真实切流动作按预案人工/编排执行（不自动执行）。
- **跨明细聚合批量重推**：`AggregatedRepushService` + `RepushBatchController`——`POST /validate-sync/reviseDiffBatchAggregated`，**桥接既有 `ValidateSyncService.reviseDiffItems` 真实通道**（非模拟），聚合→分批→逐明细回调→批次汇总。
- **存储外置**：`OversizedContentService` + `ContentOffloadController`——`POST /content-offload/store`（超 65535 字节返回 `DBDS-REF` 引用串）、`/resolve`、`/delete`；不改表结构的非破坏式扩容。
- **PL/SQL 识别**已随扫描器自动生效（`/sql-scan/scan` 结果含 SQL-P01~P06），无需新端点。
- **验证**：全模块 `clean install` **34/34 SUCCESS**（web 含 5 个新控制器）；biz 单测 **236/236**（新增 15）；新文件 UTF-8 无 BOM。
- 细节见 `reports/OPTIMIZATION_REPORT_WAVE18.md`、`reports/TEST_REPORT_WAVE18.md`。剩余边界（引擎内部插桩、存储持久化接入 task_detail 写入链路、真机 E2E 执行）已在该报告 §4 明示。

---

## 32. 2026-11-07 更新摘要（Wave19：剩余规划收口——持久化存储 / JDBC 插桩 / DBLink 读取 / 前端 UI）

用户指令「其他的规划也进行开发」对应的最后一批真实剩余项，全部落地：

- **FileChunkStore**（`biz/storage/`）：文件级持久化 `ChunkStore`（`<rootDir>/<refId>/<index>.chunk`，UTF-8），refId 路径穿越净化（`.`/`..`/尾部点号全防护），跨实例（进程重启）可恢复；与 `OversizedContentOffloader` 组合全链路可替换内存实现。
- **SlowSqlJdbcWrapper**（`biz/slowsql/`）：JDBC Statement/PreparedStatement 动态代理计时插桩，超阈值采集 `SlowSqlRecord`；`proxyConnection()` 提供零改造 Connection 形态接入；执行异常原样抛出不采集。
- **DbLinkQueryService**（`biz/dblink/`，方向五 DBLink 读取通道）：`ConnectionProvider` 注入；`all_db_links` 探测 + `@dblink` 只读查询（SELECT/WITH 白名单 + 前导注释剥离，maxRows 限行 + 截断标记 + 链接名识别）；不连真库，fake 单测。
- **前端 UI**：`public/slow-sql|logical-io|cutover|content-offload/index.html` 四页（跟随 sql-scan 模式），4 控制器零侵入新增 `@GetMapping("")` 路由，侧边栏「迁移评估」组补 4 项菜单。
- **验证**：全模块 `clean install` **34/34 SUCCESS**；biz 单测 **279/279**（新增 43：FileChunkStoreTest 15 / SlowSqlJdbcWrapperTest 14 / DbLinkQueryServiceTest 14）；新文件 13/13 UTF-8 无 BOM。
- 细节见 `reports/OPTIMIZATION_REPORT_WAVE19.md`、`reports/TEST_REPORT_WAVE19.md`。剩余待立项（引擎主链路插桩、ChunkStore 配置化接入、DBLink REST+UI、真机 E2E 执行）见该报告 §5。

---

## 33. 2026-11-07 更新摘要（Wave20：边界收口——存储配置化 + DBLink 运行时接线）

把 Wave19 §5 明示的两项待立项落地：

- **ChunkStore 配置化**：`OversizedContentService` 新增 `useMemoryStore()` / `useFileStore(rootDir)` / `storeType()`——块存储可在进程内存（默认，行为零变化）与文件持久化（Wave19 FileChunkStore，跨重启恢复）之间运行时切换；新端点 `POST /content-offload/storeConfig`（零侵入新增）；「超大内容外置」页新增块存储配置区块。
- **DBLink 运行时接线**：`DbLinkConnectionSource` 接口 + `ConnectorDbLinkConnectionSource` 生产实现（建连样板与既有 `getSchema` 同构：缓存优先、失败现场重建）+ `DbLinkQueryManager` 组合层；新控制器 `DbLinkController`——`GET /dblink` 页面（连接器下拉）、`POST /dblink/links`（all_db_links 探测）、`POST /dblink/query`（只读白名单 + @dblink + maxRows 限行）；侧边栏新增「DBLink 查询」。
- **验证**：全模块 `clean install` BUILD SUCCESS；biz 单测 **288/288**（新增 9）；新文件 6/6 无 BOM；本轮一次通过无集成修复。
- 细节见 `reports/OPTIMIZATION_REPORT_WAVE20.md`、`reports/TEST_REPORT_WAVE20.md`。真库联调边界（Oracle 源端验证、引擎插桩、task_detail 写入链路接入）见该报告 §4。

---

## 34. 2026-11-08 更新摘要（Wave21：引擎主链路慢 SQL 插桩 + task_detail 超大内容外置）

- **引擎主链路慢 SQL 自动插桩**：通过 SPI 观察点 + biz 桥接把主链路 SQL 执行耗时送入慢 SQL 采集与分析链路。
- **task_detail 超大内容自动外置**：超长明细内容按分块外置，避免单条记录撑爆存储字段。
- 细节见 `reports/OPTIMIZATION_REPORT_WAVE21.md`。

---

## 35. 2026-11-08 更新摘要（Wave22：实时链路可靠性与可观测性）

- **写入可靠性**：`WriteRetryPolicy` 异常分类（TRANSIENT / PERMANENT / UNKNOWN）与指数退避重试、`WriteFailureTag` 结构化死信标签 `[SYNC_FAIL] / [DEAD_LETTER] [kind=...] [retry=n]`；系统配置保存后即时生效。
- **链路健康**：`ReplicationEventObservers` 观察点 → `ReplicationHealthTracker` / `HealthEvaluator` → 新增 `/link-health` 页面、`/link-health/report`、`/link-health/deadLetters`、`/link-health/prometheus`（0.0.4 文本格式）；新增 `REPLICATION_HEALTH` 告警类型，按冷却窗口触发，邮件 / 企微 / 钉钉 / WebHook 全覆盖。
- **配置与前端**：系统配置新增 10 项，侧边栏「监控告警」组新增「链路健康」。
- **诚实边界**：本轮的「停滞」是距最后事件的墙钟时间，不是端到端复制延迟；死信只分类与查询、不自动重放；两项均在 Wave23 补齐。
- 细节见 `reports/OPTIMIZATION_REPORT_WAVE22.md`。

---

## 36. 2026-11-09 更新摘要（Wave23：P0/P1/P2 路线图落地 + 构建内存治理 + 服务实启）

- **P0**：真实端到端复制延迟（MySQL binlog event header → `ChangedOffset.eventTime` → 健康报告 / Prometheus / 页面，`lagSource` 区分 source / estimated / unknown）；事务型 checkpoint + 幂等键（写入成功后才提交位点，向 exactly-once 收敛）；集群 HA 文件租约接入 `ManagerFactory` 真实任务启停、关闭归还租约与定时接管。
- **P1**：死信安全重放（只有写入成功确认才删失败明细）、无主键表退化（主键 → 唯一索引 → 全行哈希）、表级 DDL 识别与人工确认、`dbdatasync-engine` 历史原型标 `@Deprecated`。
- **P2**：口令改环境变量占位符、actuator 收敛为 `health,info,metrics`、严格安全模式运行期求值、Wave17/20 持久化收口、`build.sh` 默认跑单测与 CI 流水线、Spring Boot `@EnableScheduling`。
- **内存治理**：不增加内存，改为小堆 + SerialGC + 仅 C1 + 单 fork；34 模块 `test-compile` 成功、离线可靠性套件 55/55、CI 默认全量 533/533。
- **服务实启**：`Tomcat started on port(s): 18686`，修复 H2 口令占位符未解析、MetricsEndpoint 注入失败、`SERVER__PORT` 环境变量覆盖端口三个真实问题。
- **边界**：checkpoint 是收敛而非强一致 exactly-once；HA 需 NFS/共享盘而非分布式共识；架构级开关默认关闭；发行包 jar 内静态资源需重新打包替换。
- 细节见 `reports/OPTIMIZATION_REPORT_WAVE23.md`。

---

## 37. 文档同步约定（操作手册实时更新）

- 面向最终用户的**内置操作手册**位于 `dbdatasync-web/src/main/resources/public/manual/index.html`（菜单「系统管理 → 操作手册」，路径 `/manual`，离线内置），当前基线 **Wave24 / 2026-11-09**，共 18 章，覆盖登录与菜单、迁移评估、实时链路健康、可靠性与数据一致性、47 项系统配置速查、RBAC 与 CSRF 安全基线、构建部署与 FAQ。
- **强制规则**：任何新增或修改系统配置项、侧边栏菜单、REST 端点、默认行为或页面操作步骤的变更，必须在同一次交付中更新手册对应章节、13.1 配置速查表与第 18 章变更记录，并更新页头「文档基线 / 更新日期」；只描述已接入真实运行路径的功能，规划 / 原型 / `@Deprecated` 必须显式标注边界。
- **自动卡口**：`python tools/check_manual_sync.py` 校验配置字段、菜单项、最新 Wave 基线与报告日期、Thymeleaf fragment 与 HTML 结构；CI 的 `docs-sync` 任务与 `build.sh`（`SKIP_DOCS_CHECK=1` 可临时跳过）会阻断未同步的变更。

---

## 38. 2026-11-09 更新摘要（Wave24：未完成项清账）

§22 未完成总账按「可离线闭环优先」推进，详见 `reports/OPTIMIZATION_REPORT_WAVE24.md` 与 §23 清账表。要点：

- **可观测性**：真实 lag 覆盖 PG/Kingbase（逻辑复制 commit 时间）、Oracle（LogMiner）、MongoDB（cluster time）、Kafka/RocketMQ（消息时间）、SQL Server（`lsn_time_mapping`），无源端时钟来源诚实标 `estimated`；逐行失败分类 `RowFailure` 落明细；慢 SQL 带 `taskId`；新增批写性能剖析 `/batch-profile/report`。
- **一致性与结构变更**：`TargetTxCheckpoint` 位点写入目标库 `__DBSYNC_CHECKPOINT` 同一 JDBC 事务（`enableCheckpointInTargetTx`）；表级 DDL 自动同步 + schema history + DDL-DML 顺序守卫（`enableDdlAutoSync` / `enableDdlDmlOrderGuard`）；自动切流执行器 SPI（`enableCutoverAutoExecute`，无动作处理器时明确标 `simulated`）；数据对比明细 200 条截断解除（超限外置 chunk）；SQLite Trigger CDC。
- **迁移评估**：`PlSqlAstRewriter` 轻量 AST 结构化改写（token 层保护字符串/注释，机械等价项自动改写，语义项列人工清单）；SQL 预检支持真实 EXPLAIN / 预编译执行（无连接回退静态扫描）。
- **安全与工程**：RBAC 三角色矩阵（admin/operator/viewer，严格模式逐请求求值）+ 严格模式管理动作 CSRF；`dbdatasync-engine` 模块退役（33 模块 reactor）；手工 E2E 脚本卡口入 CI；发行包全量重打包（30 jar + bin.zip 均为 Wave24）。
- **验证**：全 reactor 测试 548/548 通过（基线 533 + 新增 15，见 `TEST_REPORT_WAVE24.md`）；操作手册升级 v2.1.5，`check_manual_sync.py` 47 字段全过；18686 服务重启并线上验证新端点（`/rbac/policy`、`/batch-profile/report`、`/schema-history/*`）。
- **仍保留**（需外部条件）：真库 E2E 实跑、切流真实动作处理器、共识型 HA、编译器级 PL/SQL 语义改写。

---

## 39. 2026-11-09 更新摘要（Wave26：切流真实动作处理器 + Trigger CDC 多方言 + 真库 E2E）

§23.2 账目中三项可离线闭环项已清，详见 `reports/OPTIMIZATION_REPORT_WAVE26.md` 与规划 §24 清账表。要点：

- **切流真实动作处理器**：新增 `TaskControlActionHandler` 并接入生产执行器——`STOP_INCREMENT`/`RESUME_INCREMENT` 调用驱动真实启停（幂等），`VERIFY_TARGET` 按 `connectorId + targetSql` 在目标库真实执行校验 SQL；流量切换类外部动作仍明确标 `simulated`，不冒充已切流。
- **Trigger CDC 多方言**：通用引擎上移 SDK（`AbstractTriggerLogListener` + `TriggerCdcDialect`），SQLite 重构为方言实现（行为兼容）；扩展 **H2**（内联源码触发器 `AS $$…$$`，上下文烘焙进源码，重开库正确性实测验证；需 JDK 运行时）与 **达梦**（`:NEW`/`:OLD` SQL 体方言，SQL 生成单测通过，真实 DM 执行待外部实例）。
- **真库 E2E**：新增 `tools/e2e/RealDbPipelineE2e.java` 全链路 harness（建表→CDC 捕获→还原→目标应用→一致性→切流真实动作+SQL），支持外部连接串（`e2e.properties`）；本机以 H2/SQLite 真实文件库实跑，双 vendor 各 14 项全 PASS。
- **验证**：相关模块单测全绿 + 全 reactor `mvn test` BUILD SUCCESS；操作手册升级 v2.1.6 / 基线 Wave26，`check_manual_sync.py` 47 字段 / 22 菜单全过。
- **仍保留**（需外部条件）：达梦 Trigger CDC 真实执行、外部 MySQL/PG/Oracle 连接串下的 E2E、共识型 HA、编译器级 PL/SQL 语义改写。

## 40. 2026-11-10 更新摘要（Wave27：死信手动重放闭环 + 真库 E2E 常态回归）

详见 `reports/OPTIMIZATION_REPORT_WAVE27.md` 与规划 §25。要点：

- **死信手动重放闭环**：biz `DeadLetterReplayOrchestrator.replaySingle`（校验失败标签防误放、人工意图优先不受自动退避约束、与自动编排互不干扰、失败不删记录）→ web `POST /link-health/deadLetters/replay`（operator/admin 可执行，viewer 被 RBAC 拒绝）→【链路健康】页死信行内「重放」按钮 + 确认框 + 结果刷新；单测 7 项全过。
- **真库 E2E 接入构建**：新增 `tools/e2e/run_real_pipeline_e2e.sh`（默认 H2 vendor 无外部依赖实跑；`E2E_PROPS` 指向外部 MySQL/PG/Oracle 连接串后按同一条流水线执行；`SKIP_E2E=1` 可跳过）；`build.sh` 打包后自动执行；CI 新增 `real-pipeline-e2e` job 每次 push/PR 实跑。本机实跑 14 项全 PASS（含切流 3 步真实执行 `simulated=false`）。
- **工程卫生**：`.gitignore` 补 `tmp/` 与 E2E 中间产物，本地演练文件禁入仓库。
- **验证**：全 reactor 单测通过；操作手册升级 v2.1.7 / 基线 Wave27，`check_manual_sync.py` 47 字段 / 22 菜单全过。

---

## 42. 2026-11-10 更新摘要（Wave28：Trigger CDC 扩展到 MySQL / PostgreSQL / Oracle / SQL Server）

详见 `reports/OPTIMIZATION_REPORT_WAVE28.md` 与规划 §26。要点：

- **四个主流库 Trigger CDC 兜底捕获方言**：MySQL（免 binlog 兜底，反斜杠+单引号双转义）、PostgreSQL（PL/pgSQL 函数体，免逻辑复制兜底）、Oracle（:NEW/:OLD + PL/SQL 幂等建表，免归档兜底，需 12c+）、SQL Server（语句级触发器 inserted/deleted，免 CDC 组件兜底，需 2016+）。任务监听方式新增 `trigger` 类型，与原生日志 `log` 通道互斥切换。
- **通用引擎补强**：`pollPageSql` 方言分页（LIMIT / FETCH FIRST / TOP），修复 LIMIT 在 Oracle/SQL Server 不可执行的缺口。
- **Wave26 遗留缺口修复**：H2 / 达梦 Connector 补接线 TriggerLogListener（此前 Listener 类存在但生产不可达）。
- **验证**：7 套 Trigger CDC 测试 26 项全过（含 H2/SQLite 真库回归）；MySQL/PG/Oracle/SQL Server/达梦为 SQL 生成形状单测，真实执行需外部实例（诚实保留）。操作手册升级 v2.1.8 / 基线 Wave28。

---

## 43. 2026-11-10 更新摘要（Wave29：离线交付包重打与解压即用）

详见 `reports/OPTIMIZATION_REPORT_WAVE29.md` 与规划 §27。要点：

- **重新全量打包**：`./build.sh` 重新构建（文档同步校验 + clean + 全量单测 + assembly + 真库 E2E），把 Wave26/27/28 的代码、Wave29 的启动脚本与内置手册一并打进 `DBDataSync-2.1.4-bin.zip`；旧包停留在 11-09 18:00 的问题已消除。
- **离线可运行的启动脚本**：默认小堆 `-Xms128m -Xmx768m`（`DBS_HEAP_MIN`/`DBS_HEAP_MAX` 可覆盖）替代固定 3g / 3.8g；去掉强制 G1 16m region、CMS 与 verbose GC；`startup.sh` 先 `cd` 安装目录保证 H2 `./data` 落位；Windows `LOG_PATH` 统一为 `LOG_HOME`；`startup.sh` 增加 JRE 平台守卫。
- **离线交付物**：`tools/package_offline.py` 组装 `DBDataSync-2.1.4-offline-win64.zip`（内嵌 Windows JRE 8 + SHA256）与 `--no-jre` 的 `offline-portable.zip`；随包 `README-OFFLINE.md` 说明目录、Java 8 硬要求、启动参数、升级与端口陷阱。组装时校验内置手册基线 Wave29、切流执行器与四库 Trigger CDC 方言是否真进 jar、依赖数量、JRE `rt.jar`、zip CRC，打到旧包直接失败。
- **文档实时同步**：手册 v2.1.9 / 基线 Wave29，第 17 章重写两类包与平台边界、17.3 内存参数、17.4 组装卡口，13.1 增部署启动参数表，第 18 章基线表修正（此前停在 Wave26）。
- **运行目录同步**：`tmp/dist/lib` 同步 208 个新 jar；发现残留 `DBDataSync-2.1.4.jar`（Wave28 手册）按字母序会遮蔽新 web jar 的静态资源，已覆盖为 Wave29 内容；运行态 `conf/` 保持不动。全新目录用包内 JRE 实启验证 `/login`、`/manual`（含 Wave29）、`/link-health` 均 200。
- **诚实边界**：内嵌 JRE 为 Windows 64 位；Linux 离线环境需自带 Java 8 或放对应平台 `jre8/`；JDK 9+ 不支持 `-Djava.ext.dirs`；本轮不改变 §22 未完成总账。

---

## 44. 2026-11-10 更新摘要（Wave30：迁移能力盘点与大数据量缺陷修复）

详见 `reports/OPTIMIZATION_REPORT_WAVE30.md` 与规划 §28。要点：

- **开关与并发真正生效**：整库迁移拆成「结构阶段串行 / 数据阶段并发」；`enableCopyData=false` 时只建表不灌数据（存量任务缺字段时保留默认全量迁移语义），`threadNum` 接入表级并发池与分片读上限；【整库迁移 → 迁移策略】新增 `readNum/batchNum/threadNum` 三项可配置并夹紧到安全区间。
- **不丢数据 / 不卡死**：`writeBatch` 补齐线程池拒绝与等待超时兜底、结果合并加锁；`BatchTaskUtil` / `FullPuller` 失败上抛并保留断点；整库迁移异常路径必收尾、收尾重读最新 Meta 避免覆盖原子计数、表级快照正确落盘、失败表不再算完成。
- **断点与分片正确性**：新增 `DBDS-CURSOR-V2` 带类型位点编码（复合主键含逗号 / 前导零字符串 / 时间戳 / 旧格式兼容）；分片第一片纳入 NULL 行、`Long.MAX_VALUE` 末片闭区间、超大值域 `BigInteger` 步长。
- **大数据量内存与导出**：对账报告改两遍流式分页写出，取消单条明细 200 条与全任务 5000 行封顶；慢 SQL 启动回放只保留尾部 N 行并压缩文件；`TaskSplitUtil` 改 `subList` 消除 O(n²)；明细序列化降级改增量字节估算；限速关闭时不再逐行估算字节；结果分页 clamp + OFFSET 长整型。
- **其它真机路径缺陷**：Oracle BLOB/CLOB 循环读满并拒绝 >2GB、DBLink 查询归还连接、`DBDS-REF` refId 改 UUID 防撞、明细队列满改限时背压 + 拒绝时同步落库、反向扫描跳过可见化并按删除数回退翻页、切流改为「真实动作成功才推进相位」。
- **验证**：全模块 `mvn test` **595 tests / 0 failures / 0 errors**（76 份 surefire 报告）；新增 `Wave30MigrationScaleTest` / `Wave30OracleLobReadTest`，扩测 `FullSplitLogicTest`，改断言 `Wave12CsvBuilderTest`；手册升级 v2.2.0 / 基线 Wave30；离线包按新基线重打。
- **诚实边界**：亿级真机压测（MySQL/PG/Oracle/SQL Server）、分片断点按 slice 独立持久化、明细导出完全 keyset 化、JDBC `fetchSize` 统一、DBDS-REF 块回收、共识型 HA 与编译器级 PL/SQL 改写仍待外部条件或后续立项。

---

## 45. 2026-11-10 更新摘要（Wave31：Wave30 未完成项续做与真实验证）

详见 `reports/OPTIMIZATION_REPORT_WAVE31.md` 与规划 §29。要点：

- **分片断点隔离与真续传**：分片并发读断点按 `组ID#s+下标` 分键（此前所有分片互相覆盖组级游标，续传实际失效）；恢复时按分片边界（`start~end`）校验值域漂移，已完成分片跳过、未完成分片按各自游标续传、漂移分片从头重读；被其它分片失败中止的分片不误标完成。
- **JDBC 读侧统一 fetchSize**：`DatabaseTemplate` 默认 1000（此前 -1 等于从不设置，MySQL/SQL Server 大表整结果集逐行拉取）；`dbdatasync.jdbc.fetchSize` 可覆盖，-1 关闭，MIN_VALUE 支持 MySQL 流式。
- **DBDS-REF 外置块回收**：文件存储过期引用目录按保留期（`dbdatasync.contentOffload.retentionDays`，默认 30 天）每 6 小时自动清理，防目录无限膨胀。
- **运行服务真实验证**：恢复被杀的 18686 服务（Wave30 产物 + 小堆重启），实测 `/login`、`/manual`(Wave30)、`/link-health`、`/database-sync/pageAdd` 全 200；排障记录——shell 代理变量会污染本机探测，需 `--noproxy`。
- **验证**：新增回归 12 例（分片断点 5 / 过期清理 4 / fetchSize 3）全过；`./build.sh` BUILD_EXIT=0，全量单测 607 tests / 0 失败 / 0 错误（79 份 surefire），H2 真库 E2E 14/14 PASS；手册 v2.2.1 / 基线 Wave31（6.1、9、13.1、18 章同步）。
- **诚实边界**：明细导出完全 keyset 化、MySQL 流式 fetchSize 自动化、亿级真机压测、共识型 HA、编译器级 PL/SQL 改写仍未完成，见报告 §5。
- **发行脚本端口三重防御（Wave31 续做）**：根因定位——Spring Boot relaxed binding 会把工作台注入的 `SERVER__PORT`（双下划线，随机会话端口）与 `SERVER_PORT` 都映射为 `server.port`，且 OS 环境变量优先级高于 conf，bat 启动因此改绑随机端口，表现为「页面反复提示登录失效」或「Port already in use」。`startup.bat`/`startup.sh` 现自动清理两个变量、以命令行参数 `--server.port` 显式传入 conf 端口（最高优先级）、端口被占自动 +1 避让（≤20 次）并打印实际端口；实测污染环境绑 18686 成功、18686 被占避让 18687 成功。页面「会话过期」提示改为「登录状态已失效」并注明三种来头（手册 17.2 同步）。

---

## 46. 许可声明

本项目为开源软件，遵循开源许可协议发布。坚持开源，让每一个用户都能轻松完成数据同步。

> 本文档所述功能以当前版本代码为准，版本间可能存在差异，详情以实际系统界面与帮助文档为准。
