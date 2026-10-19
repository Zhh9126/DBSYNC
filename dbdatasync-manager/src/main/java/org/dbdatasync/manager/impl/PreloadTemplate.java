/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.manager.impl;

import org.dbdatasync.common.enums.CommonTaskStatusEnum;
import org.dbdatasync.common.enums.CommonTaskTypeEnum;
import org.dbdatasync.common.enums.TaskLevelEnum;
import org.dbdatasync.common.model.ConfigModel;
import org.dbdatasync.common.model.VersionInfo;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.DateFormatUtil;
import org.dbdatasync.common.util.JsonUtil;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.connector.base.ConnectorFactory;
import org.dbdatasync.manager.ManagerFactory;
import org.dbdatasync.parser.LogService;
import org.dbdatasync.parser.LogType;
import org.dbdatasync.parser.MetaProfile;
import org.dbdatasync.parser.ProfileComponent;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.TaskProfile;
import org.dbdatasync.parser.command.impl.PreloadCommand;
import org.dbdatasync.parser.enums.CommandEnum;
import org.dbdatasync.parser.model.Connector;
import org.dbdatasync.parser.model.Group;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.Meta;
import org.dbdatasync.parser.model.SystemConfig;
import org.dbdatasync.parser.util.ConnectorInstanceUtil;
import org.dbdatasync.plugin.PluginFactory;
import org.dbdatasync.plugin.impl.DingTalkNoticeService;
import org.dbdatasync.plugin.impl.HttpNoticeService;
import org.dbdatasync.plugin.impl.MailNoticeService;
import org.dbdatasync.plugin.impl.WeChatNoticeService;
import org.dbdatasync.sdk.connector.ConnectorInstance;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.enums.NoticeChannelEnum;
import org.dbdatasync.sdk.model.DatabaseSyncTask;
import org.dbdatasync.sdk.model.NoticeConfig;
import org.dbdatasync.sdk.model.ValidateSyncTask;
import org.dbdatasync.sdk.notice.MessageService;
import org.dbdatasync.sdk.spi.TaskService;
import org.dbdatasync.sdk.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * 预加载配置模板
 *
 * @author AE86
 * @version 1.0.0
 * @date 2019/9/16 23:59
 */
@Component
public final class PreloadTemplate implements ApplicationListener<ContextRefreshedEvent> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 版本信息
     */
    public static final String DBS_VERSION_INFO = "versionInfo";

    @Resource
    private ProfileComponent profileComponent;

    @Resource
    private TableGroupProfile tableGroupProfile;

    @Resource
    private MetaProfile metaProfile;

    @Resource
    private TaskProfile taskProfile;

    @Resource
    private ManagerFactory managerFactory;

    @Resource
    private ConnectorFactory connectorFactory;

    @Resource
    private PluginFactory pluginFactory;

    @Resource
    private StorageService storageService;

    @Resource
    private MessageService messageService;

    @Resource
    private LogService logService;

    @Resource
    private Executor generalExecutor;

    private boolean preloadCompleted;

    @Resource
    private TaskService<ValidateSyncTask> validateSyncTaskService;

    @Resource
    private TaskService<DatabaseSyncTask> databaseSyncTaskService;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {

        // Load plugins
        pluginFactory.loadPlugins();

        // Load Notification Channels
        loadNotificationChannel();

        // Load connectorInstances
        loadConnectorInstance();

        // 同步驱动：按任务级 Meta 恢复 Mapping
        launchSyncMappings();

        // 订正校验 / 整库迁移
        resumeValidateSyncTasks();
        resumeDatabaseSyncTasks();

        preloadCompleted = true;
    }

    public void loadNotificationChannel() {
        try {
            SystemConfig systemConfig = initSystemConfigIfAbsent();
            NoticeConfig noticeConfig = systemConfig.getNoticeConfig();
            if (null == noticeConfig) {
                return;
            }

            // 邮件通知
            if (noticeConfig.getMail().isEnabled()) {
                MailNoticeService service = new MailNoticeService();
                service.setUsername(noticeConfig.getMail().getAccount());
                service.setPassword(noticeConfig.getMail().getCode());
                service.build();
                messageService.registerNotifyService(NoticeChannelEnum.EMAIL, service);
            } else {
                messageService.removeNotifyService(NoticeChannelEnum.EMAIL);
            }

            // 企业微信通知
            if (noticeConfig.getWechat().isEnabled()) {
                WeChatNoticeService service = new WeChatNoticeService();
                messageService.registerNotifyService(NoticeChannelEnum.WE_CHAT, service);
            } else {
                messageService.removeNotifyService(NoticeChannelEnum.WE_CHAT);
            }

            // 钉钉通知
            if (noticeConfig.getDingTalk().isEnabled()) {
                DingTalkNoticeService service = new DingTalkNoticeService();
                messageService.registerNotifyService(NoticeChannelEnum.DING_TALK, service);
            } else {
                messageService.removeNotifyService(NoticeChannelEnum.DING_TALK);
            }

            // HTTP通知
            if (noticeConfig.getHttp().isEnabled()) {
                HttpNoticeService service = new HttpNoticeService();
                messageService.registerNotifyService(NoticeChannelEnum.HTTP, service);
            } else {
                messageService.removeNotifyService(NoticeChannelEnum.HTTP);
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * 启动时若无系统配置则写入默认行，避免后续 getSystemConfig() 为空 NPE。
     *
     * @return 已有或新建的系统配置
     */
    private SystemConfig initSystemConfigIfAbsent() {
        SystemConfig systemConfig = profileComponent.getSystemConfig();
        if (systemConfig != null) {
            return systemConfig;
        }
        systemConfig = new SystemConfig();
        systemConfig.setName("系统配置");
        long now = System.currentTimeMillis();
        systemConfig.setCreateTime(now);
        systemConfig.setUpdateTime(now);
        profileComponent.addConfigModel(systemConfig);
        logger.warn("No system config found, created default system config");
        return systemConfig;
    }

    /**
     * 是否完成预加载配置
     *
     * @return
     */
    public boolean isPreloadCompleted() {
        return preloadCompleted;
    }

    public void reload(String json) {
        Map<String, Map> map = JsonUtil.jsonToObj(json, Map.class);
        if (CollectionUtils.isEmpty(map)) {
            return;
        }
        // 版本信息检查
        Map versionInfo = map.get(DBS_VERSION_INFO);
        Assert.isTrue(versionInfo != null, "不支持导入低版本或配置不完整");
        VersionInfo info = JsonUtil.jsonToObj(versionInfo.toString(), VersionInfo.class);
        logger.info("upload config: appName={}, version={}, createTime={}", info.getAppName(), info.getVersion(), DateFormatUtil.timestampToString(new Timestamp(info.getCreateTime())));

        // Load configModels
        Stream.of(CommandEnum.PRELOAD_SYSTEM, CommandEnum.PRELOAD_USER, CommandEnum.PRELOAD_CONNECTOR, CommandEnum.PRELOAD_MAPPING, CommandEnum.PRELOAD_META)
                .forEach(commandEnum->reload(map, commandEnum));

        afterConfigImport();
    }

    /**
     * 配置导入完成后的收尾：重建连接实例，恢复同步驱动与企业任务。
     */
    public void afterConfigImport() {
        loadConnectorInstance();
        launchSyncMappings();
        resumeValidateSyncTasks();
        resumeDatabaseSyncTasks();
    }

    /**
     * 恢复同步驱动(Mapping)。
     * <p>先按任务类型 {@code mapping} 分页拉取任务，再批量查任务级 Meta（{@code isTaskDetail=0}），
     * 避免一次性加载全部 Meta。明细级 Meta 属于校验/迁移结果或表级进度，不参与驱动启停。
     */
    private void launchSyncMappings() {
        taskProfile.pageScanTasks(Mapping.class, ConfigConstant.PAGE_SIZE, mappings -> {
            if (CollectionUtils.isEmpty(mappings)) {
                return;
            }
            List<String> taskIds = new ArrayList<>();
            for (Mapping mapping : mappings) {
                if (mapping != null && StringUtil.isNotBlank(mapping.getId())) {
                    taskIds.add(mapping.getId());
                }
            }
            Map<String, Meta> metaMap = metaProfile.getTaskMetaMap(taskIds);
            if (CollectionUtils.isEmpty(metaMap)) {
                return;
            }
            for (Mapping mapping : mappings) {
                if (mapping == null || StringUtil.isBlank(mapping.getId())) {
                    continue;
                }
                Meta meta = metaMap.get(mapping.getId());
                if (meta == null) {
                    continue;
                }
                try {
                    reConnect(mapping);
                    // 恢复驱动状态（自动恢复：CDC 监听启动失败时按配置重试）
                    if (CommonTaskStatusEnum.RUNNING.getCode() == meta.getState()) {
                        managerFactory.start(mapping, true);
                    } else if (CommonTaskStatusEnum.STOPPING.getCode() == meta.getState()) {
                        managerFactory.changeMetaState(meta.getId(), CommonTaskStatusEnum.READY);
                    }
                } catch (Exception e) {
                    logger.error("恢复同步驱动失败, metaId={}, taskId={}, err={}", meta.getId(), mapping.getId(), e.getMessage(), e);
                }
            }
        });
    }

    public void reConnect(Mapping mapping) {
        reConnect(mapping.getId(), mapping.getSourceConnectorId(), mapping.getSourceDatabase(), mapping.getSourceSchema(),
                mapping.getTargetConnectorId(), mapping.getTargetDatabase(), mapping.getTargetSchema());
    }

    public void reConnect(ValidateSyncTask task) {
        //源作为查询，目标也需要作为查询 生成sql语句
        reConnect(task.getId(), task.getSourceConnectorId(), task.getSourceDatabase(), task.getSourceSchema(),
                task.getTargetConnectorId(), task.getTargetDatabase(), task.getTargetSchema());

    }

    public void reConnect(String uniqueId, String sourceConnectorId, String sourceDatabase, String sourceSchema,
                          String targetConnectorId, String targetDatabase, String targetSchema) {
        String sourceInstanceId = ConnectorInstanceUtil.buildConnectorInstanceId(uniqueId, sourceConnectorId, ConnectorInstanceUtil.SOURCE_SUFFIX);
        String targetInstanceId = ConnectorInstanceUtil.buildConnectorInstanceId(uniqueId, targetConnectorId, ConnectorInstanceUtil.TARGET_SUFFIX);
        Connector connector = profileComponent.getConnector(sourceConnectorId);
        ConnectorInstance instance = connectorFactory.connect(sourceInstanceId, connector.getConfig(), sourceDatabase, sourceSchema);
        Assert.notNull(instance, "Source connector instance can not null");
        connector = profileComponent.getConnector(targetConnectorId);
        instance = connectorFactory.connect(targetInstanceId, connector.getConfig(), targetDatabase, targetSchema);
        Assert.notNull(instance, "Target connector instance can not null");
    }

    private void reload(Map<String, Map> map, CommandEnum commandEnum) {
        reload(map, commandEnum, commandEnum.getModelType());
    }

    private void reload(Map<String, Map> map, CommandEnum commandEnum, String groupId) {
        Map config = map.get(groupId);
        if (null == config) {
            return;
        }
        Group group = JsonUtil.jsonToObj(config.toString(), Group.class);
        if (null == group || group.isEmpty()) {
            return;
        }

        for (String id : group.getIndex()) {
            Map m = map.get(id);
            ConfigModel model = (ConfigModel) commandEnum.getCommandExecutor().execute(new PreloadCommand(profileComponent, m.toString()));
            profileComponent.addConfigModel(model);
            // Load tableGroups
            if (CommandEnum.PRELOAD_MAPPING == commandEnum) {
                reload(map, CommandEnum.PRELOAD_TABLE_GROUP, tableGroupProfile.getPreloadGroupKey(model.getId()));
            }
        }
    }

    private void loadConnectorInstance() {
        List<Connector> list = profileComponent.getConnectorAll();
        if (!CollectionUtils.isEmpty(list)) {
            list.forEach(connector->generalExecutor.execute(()-> {
                try {
                    ConnectorInstance connectorInstance = connectorFactory.connect(connector.getId(), connector.getConfig(), StringUtil.EMPTY, StringUtil.EMPTY);
                    logger.info("Completed connection {} {}", connector.getConfig().getConnectorType(), connectorInstance.getServiceUrl());
                } catch (Exception e) {
                    logger.error("连接配置异常", e);
                    logService.log(LogType.ConnectorLog.FAILED, e.getMessage());
                }
            }));
        }
    }

    /**
     * 恢复订正校验任务。
     * <p>任务配置在 {@code DBDataSync_task}，表映射在 {@code DBDataSync_table_group}；
     * TaskService 企业实现启动时已从库加载缓存，此处只做连接器预热与运行中任务续跑。
     */
    private void resumeValidateSyncTasks() {
        List<ValidateSyncTask> taskAll = validateSyncTaskService.getTaskAll(CommonTaskTypeEnum.VALIDATE_SYNC);
        if (CollectionUtils.isEmpty(taskAll)) {
            return;
        }
        for (ValidateSyncTask task : taskAll) {
            try {
                reConnect(task);
            } catch (Exception e) {
                logger.error("校验任务连接器预热失败, taskId={}, err={}", task.getId(), e.getMessage(), e);
            }
        }
        resumeRunningCommonTasks(taskAll, validateSyncTaskService);
    }

    /**
     * 恢复整库迁移任务。
     * <p>库表关联已下沉 {@code DBDataSync_table_group}，不再依赖任务 JSON 内 mappings；
     * 连接器在 Handler 启动时按 table_group 初始化，此处只续跑运行中任务。
     */
    private void resumeDatabaseSyncTasks() {
        List<DatabaseSyncTask> taskAll = databaseSyncTaskService.getTaskAll(CommonTaskTypeEnum.DATABASE_SYNC);
        if (CollectionUtils.isEmpty(taskAll)) {
            return;
        }
        resumeRunningCommonTasks(taskAll, databaseSyncTaskService);
    }

    /**
     * 将中断前 Meta.state=RUNNING 的任务重新拉起（先将 Meta 置 READY，再 start）。
     */
    private <T extends ConfigModel> void resumeRunningCommonTasks(List<T> taskAll, TaskService<T> taskService) {
        for (T task : taskAll) {
            if (task == null || StringUtil.isBlank(task.getId())) {
                continue;
            }
            Meta meta = metaProfile.getMetaByTaskId(task.getId(), TaskLevelEnum.TASK);
            if (meta == null || meta.getState() != CommonTaskStatusEnum.RUNNING.getCode()) {
                continue;
            }
            try {
                meta.setState(CommonTaskStatusEnum.READY.getCode());
                meta.setUpdateTime(System.currentTimeMillis());
                profileComponent.editConfigModel(meta);
                taskService.start(task.getId());
                logger.info("已恢复运行中任务: type={}, taskId={}, name={}", task.getType(), task.getId(), task.getName());
            } catch (Exception e) {
                logger.error("恢复任务失败, taskId={}, err={}", task.getId(), e.getMessage(), e);
            }
        }
    }
}