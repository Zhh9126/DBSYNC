/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.biz.impl;

import org.dbdatasync.biz.BizException;
import org.dbdatasync.biz.PluginService;
import org.dbdatasync.biz.vo.PluginVO;
import org.dbdatasync.common.enums.FileSuffixEnum;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.parser.LogService;
import org.dbdatasync.parser.LogType;
import org.dbdatasync.parser.ParserException;
import org.dbdatasync.parser.TableGroupProfile;
import org.dbdatasync.parser.TaskProfile;
import org.dbdatasync.parser.model.Mapping;
import org.dbdatasync.parser.model.TableGroup;
import org.dbdatasync.plugin.PluginFactory;
import org.dbdatasync.sdk.constant.ConfigConstant;
import org.dbdatasync.sdk.model.Plugin;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2020/01/13 17:18
 */
@Component
public class PluginServiceImpl implements PluginService {

    @Resource
    private PluginFactory pluginFactory;

    @Resource
    private TaskProfile taskProfile;

    @Resource
    private TableGroupProfile tableGroupProfile;

    @Resource
    private LogService logService;

    @Override
    public List<PluginVO> listPlugins() {
        List<Plugin> pluginAll = pluginFactory.getPluginAll();
        if (CollectionUtils.isEmpty(pluginAll)) {
            return new ArrayList<>();
        }
        return pluginAll.stream().map(this::toPluginVO).collect(Collectors.toList());
    }

    @Override
    public List<PluginVO> getPluginAll() {
        List<Plugin> pluginAll = pluginFactory.getPluginAll();
        if (CollectionUtils.isEmpty(pluginAll)) {
            return new ArrayList<>();
        }
        Map<String, List<String>> pluginClassNameMap = getPluginClassNameMap();
        return pluginAll.stream().map(plugin -> {
            PluginVO vo = toPluginVO(plugin);
            vo.setMappingName(StringUtil.join(pluginClassNameMap.get(plugin.getClassName()), StringUtil.VERTICAL_LINE));
            return vo;
        }).collect(Collectors.toList());
    }

    private PluginVO toPluginVO(Plugin plugin) {
        PluginVO vo = new PluginVO();
        BeanUtils.copyProperties(plugin, vo);
        return vo;
    }

    @Override
    public String getPluginPath() {
        return pluginFactory.getPluginPath();
    }

    @Override
    public String getLibraryPath() {
        return pluginFactory.getLibraryPath();
    }

    @Override
    public void loadPlugins() {
        pluginFactory.loadPlugins();
        logService.log(LogType.PluginLog.UPDATE);
    }

    @Override
    public void checkFileSuffix(String filename) {
        Assert.hasText(filename, "the plugin filename is null.");
        String suffix = filename.substring(filename.lastIndexOf(".") + 1, filename.length());
        FileSuffixEnum fileSuffix = FileSuffixEnum.getFileSuffix(suffix);
        Assert.notNull(fileSuffix, "Illegal file suffix");
        if (FileSuffixEnum.JAR != fileSuffix) {
            String msg = String.format("不正确的文件扩展名 \"%s\"，只支持 \"%s\" 的文件扩展名。", filename, FileSuffixEnum.JAR.getName());
            logService.log(LogType.PluginLog.CHECK_ERROR, msg);
            throw new BizException(msg);
        }
    }

    private Map<String, List<String>> getPluginClassNameMap() {
        Map<String, List<String>> map = new ConcurrentHashMap<>();
        taskProfile.pageScanTasks(Mapping.class, ConfigConstant.PAGE_SIZE, mappingAll -> {
            if (CollectionUtils.isEmpty(mappingAll)) {
                return;
            }
            for (Mapping m : mappingAll) {
                Plugin plugin = m.getPlugin();
                if (null != plugin) {
                    putPluginMap(map, plugin.getClassName(), m.getName());
                    continue;
                }
                AtomicBoolean pluginFound = new AtomicBoolean(false);
                tableGroupProfile.pageScanTableGroups(m.getId(), ConfigConstant.PAGE_SIZE, page -> {
                    if (pluginFound.get() || CollectionUtils.isEmpty(page)) {
                        return;
                    }
                    for (TableGroup t : page) {
                        if (t == null) {
                            continue;
                        }
                        Plugin p = t.getPlugin();
                        if (p != null) {
                            putPluginMap(map, p.getClassName(), m.getName());
                            pluginFound.set(true);
                            return;
                        }
                    }
                });
            }
        });
        return map;
    }

    private void putPluginMap(Map<String, List<String>> map, String className, String name) {
        map.compute(className, (k, v)-> {
            if (v == null) {
                try {
                    return new ArrayList<>();
                } catch (Exception e) {
                    throw new ParserException(e);
                }
            }
            return v;
        }).add(name);
    }
}