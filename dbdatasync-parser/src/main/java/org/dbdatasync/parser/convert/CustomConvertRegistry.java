package org.dbdatasync.parser.convert;

import org.dbdatasync.common.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义转换函数注册表（产品化扩展点）
 *
 * <p>内置转换见 {@link org.dbdatasync.parser.enums.ConvertEnum}；客户/交付侧可通过
 * JVM 参数 {@code -Ddbdatasync.convert.custom=/path/convert-custom.properties} 注册自定义函数，
 * 属性文件每行一条：{@code 转换编码=Handler 实现类全名}（实现
 * {@link org.dbdatasync.parser.convert.Handler} 接口，产品的classpath 需包含该实现 jar）。
 * 注册后再字段的转换配置（convert）中引用该转换编码即可在同步/迁移中生效。</p>
 *
 * <p>示例（properties）：
 * <pre>
 *     DB_TO_UPPER=com.customer.convert.DbToUpperHandler
 * </pre></p>
 *
 * @author DBDataSync
 * @version 1.0.0
 * @date 2026-10-17
 */
public abstract class CustomConvertRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomConvertRegistry.class);

    /** 自定义函数注册表：转换编码 → handler */
    private static final Map<String, Handler> HANDLERS = new ConcurrentHashMap<>();

    private static volatile boolean loaded = false;

    /**
     * 注册自定义转换函数（代码内扩展亦可使用）
     */
    public static void register(String code, Handler handler) {
        if (StringUtil.isNotBlank(code) && handler != null) {
            HANDLERS.put(code, handler);
            LOGGER.info("Registered custom convert handler: {}", code);
        }
    }

    /**
     * 按转换编码查询 handler（优先自定义注册，未注册返回 null）
     */
    public static Handler lookup(String code) {
        loadConfiguredHandlers();
        return HANDLERS.get(code);
    }

    private static void loadConfiguredHandlers() {
        if (loaded) {
            return;
        }
        synchronized (CustomConvertRegistry.class) {
            if (loaded) {
                return;
            }
            loaded = true;
            String path = System.getProperty("dbdatasync.convert.custom");
            if (StringUtil.isBlank(path)) {
                return;
            }
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    LOGGER.warn("Custom convert config not found on classpath: {}", path);
                    return;
                }
                Properties properties = new Properties();
                properties.load(in);
                for (String code : properties.stringPropertyNames()) {
                    String className = properties.getProperty(code);
                    try {
                        register(code, (Handler) Class.forName(className).newInstance());
                    } catch (Exception e) {
                        LOGGER.error("Load custom convert handler failed: {}={}", code, className, e);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Load custom convert config failed: {}", path, e);
            }
        }
    }
}
