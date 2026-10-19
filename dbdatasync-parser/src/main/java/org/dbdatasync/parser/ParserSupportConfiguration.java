/**
 * DBDataSync Copyright 2020-2024 All Rights Reserved.
 */
package org.dbdatasync.parser;

import org.dbdatasync.parser.flush.impl.DefaultBufferActuatorRouter;
import org.dbdatasync.parser.flush.impl.TableGroupBufferActuator;
import org.dbdatasync.sdk.spi.BufferActuatorRouterService;
import org.dbdatasync.sdk.spi.ServiceFactory;
import org.dbdatasync.sdk.spi.TableGroupBufferActuatorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;

import javax.annotation.Resource;

/**
 * @author AE86
 * @version 1.0.0
 * @date 2024-01-25 23:43
 */
@Configuration
public class ParserSupportConfiguration {

    @Resource
    private ServiceFactory serviceFactory;

    /**
     * 表执行器原型：开源回落 {@link TableGroupBufferActuator}，企业版 SPI 为 Plus。
     * 路由 bind 时通过 {@link TableGroupBufferActuatorService#clone()} 派生每表/每管道实例。
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean
    @DependsOn(value = "serviceFactory")
    public TableGroupBufferActuatorService tableGroupBufferActuatorService() {
        TableGroupBufferActuatorService service = serviceFactory.get(TableGroupBufferActuatorService.class);
        if (service != null) {
            return service;
        }
        return new TableGroupBufferActuator();
    }

    @Bean
    @ConditionalOnMissingBean
    @DependsOn(value = "serviceFactory")
    public BufferActuatorRouterService bufferActuatorRouterService() {
        BufferActuatorRouterService service = serviceFactory.get(BufferActuatorRouterService.class);
        if (service != null) {
            return service;
        }
        return new DefaultBufferActuatorRouter();
    }

}