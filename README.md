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

## 17. 许可声明

本项目为开源软件，遵循开源许可协议发布。坚持开源，让每一个用户都能轻松完成数据同步。

> 本文档所述功能以当前版本代码为准，版本间可能存在差异，详情以实际系统界面与帮助文档为准。
