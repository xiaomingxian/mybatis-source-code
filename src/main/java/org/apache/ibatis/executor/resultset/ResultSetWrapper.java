/**
 * Copyright 2009-2017 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.executor.resultset;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.apache.ibatis.type.UnknownTypeHandler;

/**
 * @author Iwao AVE!
 */
public class ResultSetWrapper {

    // 结果集
    private final ResultSet resultSet;
    // TypeHandler注册器
    private final TypeHandlerRegistry typeHandlerRegistry;
    // 结果的列名集合
    private final List<String> columnNames = new ArrayList<String>();
    // 对应的实体属性类型集合
    private final List<String> classNames = new ArrayList<String>();
    // 对应的JDBC类型集合
    private final List<JdbcType> jdbcTypes = new ArrayList<JdbcType>();
    private final Map<String, Map<Class<?>, TypeHandler<?>>> typeHandlerMap = new HashMap<>();
    private Map<String, List<String>> mappedColumnNamesMap = new HashMap<String, List<String>>();
    private Map<String, List<String>> unMappedColumnNamesMap = new HashMap<String, List<String>>();

    // eg1:
    public ResultSetWrapper(ResultSet rs, Configuration configuration) throws SQLException {
        super();
        this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        this.resultSet = rs;
        final ResultSetMetaData metaData = rs.getMetaData();
        // eg1: columnCount = 3
        /** 获得查询结果列的数量 */
        final int columnCount = metaData.getColumnCount();
        for (int i = 1; i <= columnCount; i++) {
            // eg1: configuration.isUseColumnLabel() = true
            // metaData.getColumnLabel(1) = metaData.getColumnName(1) = "id"
            // metaData.getColumnLabel(2) = metaData.getColumnName(2) = "name"
            // metaData.getColumnLabel(3) = metaData.getColumnName(3) = "age"
            columnNames.add(configuration.isUseColumnLabel() ? metaData.getColumnLabel(i) : metaData.getColumnName(i));
            // JdbcType.forCode(metaData.getColumnType(1)) = "BIGINT"
            // JdbcType.forCode(metaData.getColumnType(2)) = "VARCHAR"
            // JdbcType.forCode(metaData.getColumnType(3)) = "INTEGER"
            jdbcTypes.add(JdbcType.forCode(metaData.getColumnType(i)));
            // metaData.getColumnClassName(1) = "java.lang.Long"
            // metaData.getColumnClassName(2) = "java.lang.String"
            // metaData.getColumnClassName(3) = "java.lang.Integer
            classNames.add(metaData.getColumnClassName(i));
        }
    }

    public ResultSet getResultSet() {
        return resultSet;
    }

    public List<String> getColumnNames() {
        return this.columnNames;
    }

    public List<String> getClassNames() {
        return Collections.unmodifiableList(classNames);
    }

    public JdbcType getJdbcType(String columnName) {
        for (int i = 0; i < columnNames.size(); i++) {
            if (columnNames.get(i).equalsIgnoreCase(columnName)) {
                return jdbcTypes.get(i);
            }
        }
        return null;
    }

    /**
     * Gets the type handler to use when reading the result set.
     * Tries to get from the TypeHandlerRegistry by searching for the property type.
     * If not found it gets the column JDBC type and tries to get a handler for it.
     *
     * @param propertyType
     * @param columnName
     * @return
     */
    public TypeHandler<?> getTypeHandler(Class<?> propertyType, String columnName) {
        TypeHandler<?> handler = null;
        Map<Class<?>, TypeHandler<?>> columnHandlers = typeHandlerMap.get(columnName);
        if (columnHandlers == null) {
            columnHandlers = new HashMap<Class<?>, TypeHandler<?>>();
            typeHandlerMap.put(columnName, columnHandlers);
        } else {
            handler = columnHandlers.get(propertyType);
        }
        if (handler == null) {
            JdbcType jdbcType = getJdbcType(columnName);
            handler = typeHandlerRegistry.getTypeHandler(propertyType, jdbcType);
            // Replicate logic of UnknownTypeHandler#resolveTypeHandler
            // See issue #59 comment 10
            if (handler == null || handler instanceof UnknownTypeHandler) {
                final int index = columnNames.indexOf(columnName);
                final Class<?> javaType = resolveClass(classNames.get(index));
                if (javaType != null && jdbcType != null) {
                    handler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
                } else if (javaType != null) {
                    handler = typeHandlerRegistry.getTypeHandler(javaType);
                } else if (jdbcType != null) {
                    handler = typeHandlerRegistry.getTypeHandler(jdbcType);
                }
            }
            if (handler == null || handler instanceof UnknownTypeHandler) {
                handler = new ObjectTypeHandler();
            }
            columnHandlers.put(propertyType, handler);
        }
        return handler;
    }

    private Class<?> resolveClass(String className) {
        try {
            // #699 className could be null
            if (className != null) {
                return Resources.classForName(className);
            }
        } catch (ClassNotFoundException e) {
            // ignore
        }
        return null;
    }

    // eg1: columnPrefix=null
    /**
     * 加载映射的列（mappedColumnNamesMap）与未映射的列（unMappedColumnNamesMap）
     */
    private void loadMappedAndUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
        List<String> mappedColumnNames = new ArrayList<>();
        List<String> unmappedColumnNames = new ArrayList<>();
        final String upperColumnPrefix = columnPrefix == null ? null : columnPrefix.toUpperCase(Locale.ENGLISH);
        // eg1: resultMap.getMappedColumns().size=0  upperColumnPrefix=null
        final Set<String> mappedColumns = prependPrefixes(resultMap.getMappedColumns(), upperColumnPrefix); // eg1: mappedColumns.size()=0
        // eg1: columnNames={"id"，"name"，"age"}
        for (String columnName : columnNames) {
            // eg1: upperColumnName="ID"
            // eg1: upperColumnName="NAME"
            // eg1: upperColumnName="AGE"
            final String upperColumnName = columnName.toUpperCase(Locale.ENGLISH);
            if (mappedColumns.contains(upperColumnName)) {
                mappedColumnNames.add(upperColumnName);
            } else {
                // eg1: unmappedColumnNames={"id"}
                // eg1: unmappedColumnNames={"id", "name"}
                // eg1: unmappedColumnNames={"id", "name", "age"}
                unmappedColumnNames.add(columnName);
            }
        }
        // eg1: getMapKey(resultMap, columnPrefix)=mapper.UserMapper.getUserById-Inline:null
        //      mappedColumnNames.key="mapper.UserMapper.getUserById-Inline:null"
        //      mappedColumnNames.value={}
        mappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), mappedColumnNames);

        // eg1: getMapKey(resultMap, columnPrefix)=mapper.UserMapper.getUserById-Inline:null
        //      unmappedColumnNames.key="mapper.UserMapper.getUserById-Inline:null"
        //      unmappedColumnNames.value={"id", "name", "age"}
        unMappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), unmappedColumnNames);
    }

    // eg1: columnPrefix=null
    public List<String> getMappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
        // eg1: getMapKey(resultMap, columnPrefix)="mapper.UserMapper.getUserById-Inline:null"
        List<String> mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        // eg1: mappedColumnNames是空的列表，但不是null
        if (mappedColumnNames == null) {
            /** 加载映射的列（mappedColumnNamesMap）与未映射的列（unMappedColumnNamesMap） */
            loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
            mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        }
        return mappedColumnNames; // eg1: 返回空的列表
    }

    // eg1: columnPrefix=null
    public List<String> getUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
        // eg1: getMapKey(resultMap, columnPrefix)=mapper.UserMapper.getUserById-Inline:null    unMappedColumnNamesMap.size()=0
        List<String> unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));

        // eg1: unMappedColumnNames=null
        if (unMappedColumnNames == null) {
            // eg1: columnPrefix=null
            loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
            // eg1: unMappedColumnNames={"id", "name", "age"}
            unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        }
        return unMappedColumnNames; // eg1: unMappedColumnNames={"id", "name", "age"}
    }

    private String getMapKey(ResultMap resultMap, String columnPrefix) {
        return resultMap.getId() + ":" + columnPrefix;
    }

    // eg1: columnNames.size()=0  prefix=null
    private Set<String> prependPrefixes(Set<String> columnNames, String prefix) {
        // eg1: columnNames.size()=0  columnNames.isEmpty()=true  prefix=null
        if (columnNames == null || columnNames.isEmpty() || prefix == null || prefix.length() == 0) {
            return columnNames; // eg1: columnNames.size()=0
        }
        final Set<String> prefixed = new HashSet<>();
        for (String columnName : columnNames) {
            prefixed.add(prefix + columnName);
        }
        return prefixed;
    }

}
