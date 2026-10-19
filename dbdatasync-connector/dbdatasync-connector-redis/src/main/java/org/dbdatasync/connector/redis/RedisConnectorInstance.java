/**
 * DBDataSync Copyright 2020-2026 All Rights Reserved.
 */
package org.dbdatasync.connector.redis;

import org.dbdatasync.connector.redis.config.RedisConfig;
import org.dbdatasync.connector.redis.util.RedisUtil;
import org.dbdatasync.sdk.connector.ConnectorInstance;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Redis连接器实例
 */
public final class RedisConnectorInstance implements ConnectorInstance<RedisConfig, JedisPool> {

    private RedisConfig config;
    private final JedisPool pool;

    public RedisConnectorInstance(RedisConfig config) {
        this.config = config;
        this.pool = RedisUtil.createPool(config);
        ping();
    }

    @Override
    public String getServiceUrl() {
        return config.getUrl();
    }

    @Override
    public RedisConfig getConfig() {
        return config;
    }

    @Override
    public void setConfig(RedisConfig config) {
        this.config = config;
    }

    @Override
    public JedisPool getConnection() {
        return pool;
    }

    public Jedis borrowJedis() {
        return pool.getResource();
    }

    public void ping() {
        try (Jedis jedis = borrowJedis()) {
            jedis.ping();
        }
    }

    @Override
    public void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}
