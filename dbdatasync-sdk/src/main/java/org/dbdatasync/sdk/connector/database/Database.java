/**
 * DBDataSync Copyright 2020-2023 All Rights Reserved.
 */
package org.dbdatasync.sdk.connector.database;

import net.sf.jsqlparser.statement.alter.Alter;
import org.dbdatasync.common.util.CollectionUtils;
import org.dbdatasync.common.util.SqlIdentifierValidator;
import org.dbdatasync.common.util.StringUtil;
import org.dbdatasync.sdk.config.SqlBuilderConfig;
import org.dbdatasync.sdk.enums.SqlBuilderEnum;
import org.dbdatasync.sdk.model.Field;
import org.dbdatasync.sdk.model.PageSql;
import org.dbdatasync.sdk.model.ValidateSyncTask;
import org.dbdatasync.sdk.plugin.ReaderContext;

import java.util.List;
import java.util.stream.Collectors;

public interface Database {

    /**
     * 获取dbs唯一标识码
     */
    default String generateUniqueCode() {
        return StringUtil.EMPTY;
    }

    /**
     * 获取引号（默认不加）
     */
    default String buildSqlWithQuotation() {
        return StringUtil.EMPTY;
    }

    /**
     * 返回带引号的名称。
     *
     * <p>对标识符进行 SQL 注入白名单校验（由 {@link SqlIdentifierValidator#validateIdentifier} 完成），
     * 防止通过表名/列名注入恶意 SQL。校验不通过将抛出 {@link IllegalArgumentException}，阻断危险 SQL 拼装。</p>
     */
    default String buildWithQuotation(String name) {
        String safe = SqlIdentifierValidator.validateIdentifier(name);
        return buildSqlWithQuotation() + safe + buildSqlWithQuotation();
    }

    /**
     * 生成创建数据库（或同级命名空间）的 DDL；已存在时不报错（各库方言由连接器覆盖）。
     */
    String buildCreateDatabaseSql(String databaseName, String schemaName);

    /**
     * 判断目标命名空间是否存在（库名 + Schema 由连接器方言解释）。
     */
    boolean databaseExists(DatabaseConnectorInstance connectorInstance, String databaseName, String schemaName);

    /**
     * 生成创建表 DDL
     */
    String getTargetTableDDL(DatabaseConnectorInstance targetInstance, String tableName, String sourceDDL);

    /**
     * 从源库获取建表DDL
     */
    String getSourceTableDDL(DatabaseConnectorInstance sourceInstance, String sourceTableName);

    /**
     * 生成删除表 DDL；targetInstance
     */
    String buildDropTableSql(DatabaseConnectorInstance targetInstance, String tableName);

    /**
     * 获取主键字段名称
     */
    default List<String> buildPrimaryKeys(List<String> primaryKeys) {
        if (CollectionUtils.isEmpty(primaryKeys)) {
            return primaryKeys;
        }
        return primaryKeys.stream().map(this::buildWithQuotation).collect(Collectors.toList());
    }

    /**
     * 追加主键和参数占位符
     *
     * @param sql
     * @param primaryKeys
     */
    default void appendPrimaryKeys(StringBuilder sql, List<String> primaryKeys) {
        if (CollectionUtils.isEmpty(primaryKeys)) {
            return;
        }
        List<String> pks = primaryKeys.stream().map(name -> buildWithQuotation(name) + "=?").collect(Collectors.toList());
        sql.append(StringUtil.join(pks, " AND "));
    }

    /**
     * 获取分页SQL
     *
     * @param config
     * @return
     */
    String getPageSql(PageSql config);

    /**
     * 获取分页参数
     *
     * @param context
     * @return
     */
    Object[] getPageArgs(ReaderContext context);

    /**
     * 获取游标分页SQL
     *
     * @param pageSql
     * @return
     */
    String getPageCursorSql(PageSql pageSql);

    /**
     * 获取游标分页参数
     *
     * @param context
     * @return
     */
    Object[] getPageCursorArgs(ReaderContext context);

    /**
     * 批量构建 MODIFY / ALTER COLUMN 语句。
     * 默认实现回退为逐列拼接；建议各数据库方言覆盖为原生批量语法。
     *
     * @param targetInstance    目标连接实例
     * @param task              校验任务
     * @param targetTableName   目标表名
     * @param sourceDefinitions 源端字段定义列表（期望形态）
     * @param targetColumnNames 目标列名列表（与 sourceDefinitions 一一对应）
     * @return 可执行批量 DDL
     */
    String buildModifyColumnsSql(DatabaseConnectorInstance targetInstance, ValidateSyncTask task,
                                 String targetTableName, List<Field> sourceDefinitions,
                                 List<String> targetColumnNames);

    /**
     * 生成列定义中的类型片段(目标方言), 输入为源端字段定义。
     * 用于跨源 DDL 翻译与同构订正。
     */
    String formatPhysicalType(Field sourceDefinition);

    /**
     * 批量构建 ADD COLUMN 语句(跨源 DDL 同步使用)。
     * 默认 MySQL/PostgreSQL/Kingbase 兼容语法; Oracle/SqlServer 等方言自行覆盖。
     *
     * @param targetInstance  目标连接实例
     * @param targetTableName 目标表名(不含库名前缀)
     * @param qualifiedName   带库名前缀的限定表名(方言自行决定用哪个)
     * @param fields          新增列的源端字段定义(经 formatPhysicalType 翻译为目标方言类型)
     * @return 可执行 DDL; 无法翻译时返回空串
     */
    default String buildAddColumnsSql(DatabaseConnectorInstance targetInstance, String targetTableName,
                                      String qualifiedName, List<Field> fields) {
        if (fields == null || fields.isEmpty()) {
            return "";
        }
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(qualifiedName);
        for (Field field : fields) {
            sql.append(" ADD COLUMN ").append(buildWithQuotation(field.getName()))
                    .append(" ").append(formatPhysicalType(field));
        }
        return sql.toString();
    }

    /**
     * 批量构建 DROP COLUMN 语句(跨源 DDL 同步使用)。
     */
    default String buildDropColumnsSql(DatabaseConnectorInstance targetInstance, String targetTableName,
                                        String qualifiedName, List<String> columnNames) {
        if (columnNames == null || columnNames.isEmpty()) {
            return "";
        }
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(qualifiedName);
        for (String name : columnNames) {
            sql.append(" DROP COLUMN ").append(buildWithQuotation(name));
        }
        return sql.toString();
    }

    /**
     * 健康检查
     *
     * @return
     */
    default String getValidationQuery() {
        return "select 1";
    }

    /**
     * 获取查询总数SQL
     */
    default String getQueryCountSql(SqlBuilderConfig sqlConfig) {
        return SqlBuilderEnum.QUERY_COUNT.getSqlBuilder().buildSql(sqlConfig);
    }

    /**
     * 查询目标总数表
     */
    default String getQueryTargetCountSql(SqlBuilderConfig sqlConfig) {
        return SqlBuilderEnum.TARGET_QUERY_COUNT.getSqlBuilder().buildSql(sqlConfig);
    }

    /**
     * 生成upsert
     */
    String buildUpsertSql(DatabaseConnectorInstance connectorInstance, SqlBuilderConfig config);

    /**
     * 生成insert
     */
    default String buildInsertSql(SqlBuilderConfig config) {
        return SqlBuilderEnum.INSERT.getSqlBuilder().buildSql(config);
    }

    default boolean buildCustom(List<String> fs, Field field) {
        return false;
    }

    /**
     * 为特殊字段类型构建自定义的值表达式
     *
     * <p>用于 INSERT/UPDATE 语句的 VALUES 部分，允许数据库连接器为特定字段类型（如 geometry、geography）
     * 提供自定义的 SQL 表达式，而不是简单的占位符 ?</p>
     *
     * <p>例如 SQL Server 的 geometry 类型需要使用 geometry::STGeomFromText(?, ?) 来转换</p>
     *
     * @param vs    值表达式列表（VALUES 部分）
     * @param field 字段信息
     * @return true 表示已添加自定义值表达式，false 表示使用默认的 ? 占位符
     */
    default boolean buildCustomValue(List<String> vs, Field field) {
        return false;
    }

    /**
     * 替换ddl语句中的catalog
     *
     * @param connectorInstance
     * @param alter
     * @return
     */
    default String buildAlterCatalog(DatabaseConnectorInstance connectorInstance, Alter alter) {
        return alter.toString();
    }
}