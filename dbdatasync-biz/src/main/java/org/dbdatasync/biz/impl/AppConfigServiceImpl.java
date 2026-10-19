/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.biz.impl;

import org.dbdatasync.biz.AppConfigService;
import org.dbdatasync.biz.SystemConfigService;
import org.dbdatasync.biz.vo.VersionVO;
import org.dbdatasync.common.config.AppConfig;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.parser.model.SystemConfig;

import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * @Author AE86
 * @Version 1.0.0
 * @Date 2024-05-12 01:08
 */
@Component
public class AppConfigServiceImpl implements AppConfigService {

    @Resource
    private AppConfig appConfig;

    @Resource
    private SystemConfigService systemConfigService;

    public static final String WATERMARK_USERNAME = "${username}";

    @Override
    public VersionVO getVersionInfo(String username) {
        VersionVO versionVo = new VersionVO(appConfig.getName(), appConfig.getCopyright());
        // 是否启用水印
        SystemConfig systemConfig = systemConfigService.getSystemConfig();
        if (systemConfig.isEnableWatermark()) {
            String watermark = systemConfigService.getWatermark(systemConfig);
            watermark = StringUtil.replace(watermark, WATERMARK_USERNAME, username);
            versionVo.setWatermark(watermark);
        }
        return versionVo;
    }
}
