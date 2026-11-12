package org.dbdatasync.biz.vo;

import org.dbdatasync.common.util.StringUtil;

/**
 * @version 1.0.0
 * @Author AE86
 * @Date 2021-01-27 20:55
 */
public class VersionVO {

    /**
     * 应用名称
     */
    private final String appName;
    /**
     * 版权详细
     */
    private final String appCopyRight;
    /**
     * 水印
     */
    private String watermark;
    /**
     * 产品版本号(取自构建 jar 的 Implementation-Version, 随每次发布自动更新)
     */
    private final String version;

    public VersionVO(String appName, String appCopyRight) {
        this.appName = appName;
        this.appCopyRight = appCopyRight;
        this.version = resolveVersion();
    }

    /**
     * 从当前 jar 的 MANIFEST Implementation-Version 读取发布版本;
     * IDE/未打包运行时取不到则回退常量。版本随 pom 版本发布自动更新。
     */
    private static String resolveVersion() {
        try {
            String v = VersionVO.class.getPackage().getImplementationVersion();
            if (StringUtil.isNotBlank(v)) {
                return v;
            }
        } catch (Exception ignored) {
        }
        return "2.1.4";
    }

    public String getVersion() {
        return version;
    }

    public String getAppName() {
        return appName;
    }

    public String getAppCopyRight() {
        return appCopyRight;
    }

    public String getWatermark() {
        return watermark;
    }

    public void setWatermark(String watermark) {
        this.watermark = watermark;
    }
}
