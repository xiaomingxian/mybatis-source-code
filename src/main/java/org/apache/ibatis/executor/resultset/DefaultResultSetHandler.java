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

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

    private static final Object DEFERED = new Object();

    private final Executor executor;
    private final Configuration configuration;
    private final MappedStatement mappedStatement;
    private final RowBounds rowBounds;
    private final ParameterHandler parameterHandler;
    private final ResultHandler<?> resultHandler;
    private final BoundSql boundSql;
    private final TypeHandlerRegistry typeHandlerRegistry;
    private final ObjectFactory objectFactory;
    private final ReflectorFactory reflectorFactory;

    // nested resultmaps
    private final Map<CacheKey, Object> nestedResultObjects = new HashMap<CacheKey, Object>();
    private final Map<String, Object> ancestorObjects = new HashMap<String, Object>();
    private Object previousRowValue;

    // multiple resultsets
    private final Map<String, ResultMapping> nextResultMaps = new HashMap<String, ResultMapping>();
    private final Map<CacheKey, List<PendingRelation>> pendingRelations =
            new HashMap<CacheKey, List<PendingRelation>>();

    // Cached Automappings
    private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache =
            new HashMap<String, List<UnMappedColumnAutoMapping>>();

    // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
    private boolean useConstructorMappings;

    private final PrimitiveTypes primitiveTypes;

    private static class PendingRelation {
        public MetaObject metaObject;
        public ResultMapping propertyMapping;
    }

    private static class UnMappedColumnAutoMapping {
        private final String column;
        private final String property;
        private final TypeHandler<?> typeHandler;
        private final boolean primitive;

        public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler,
                                         boolean primitive) {
            this.column = column;
            this.property = property;
            this.typeHandler = typeHandler;
            this.primitive = primitive;
        }
    }

    public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement,
                                   ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
                                   RowBounds rowBounds) {
        this.executor = executor;
        this.configuration = mappedStatement.getConfiguration();
        this.mappedStatement = mappedStatement;
        this.rowBounds = rowBounds;
        this.parameterHandler = parameterHandler;
        this.boundSql = boundSql;
        this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        this.objectFactory = configuration.getObjectFactory();
        this.reflectorFactory = configuration.getReflectorFactory();
        this.resultHandler = resultHandler;
        this.primitiveTypes = new PrimitiveTypes();
    }

    //
    // HANDLE OUTPUT PARAMETER
    //

    @Override
    public void handleOutputParameters(CallableStatement cs) throws SQLException {
        final Object parameterObject = parameterHandler.getParameterObject();
        final MetaObject metaParam = configuration.newMetaObject(parameterObject);
        final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        for (int i = 0; i < parameterMappings.size(); i++) {
            final ParameterMapping parameterMapping = parameterMappings.get(i);
            if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
                if (ResultSet.class.equals(parameterMapping.getJavaType())) {
                    handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
                } else {
                    final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
                    metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
                }
            }
        }
    }

    private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam)
            throws SQLException {
        if (rs == null) {
            return;
        }
        try {
            final String resultMapId = parameterMapping.getResultMapId();
            final ResultMap resultMap = configuration.getResultMap(resultMapId);
            final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
            final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
            handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
            metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
        } finally {
            // issue #228 (close resultsets)
            closeResultSet(rs);
        }
    }

    /**
     * 处理数据库操作的结果集
     */
    // eg1: 执行到这里
    @Override
    public List<Object> handleResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

        final List<Object> multipleResults = new ArrayList<>();
        int resultSetCount = 0;

        /** 首先：获得执行后的结果集，并封装到ResultSetWrapper */
        ResultSetWrapper rsw = getFirstResultSet(stmt);

        /** 其次：如果rsw != null && resultMapCount < 1，则抛异常ExecutorException */
        List<ResultMap> resultMaps = mappedStatement.getResultMaps();
        int resultMapCount = resultMaps.size(); // eg1: resultMapCount = 1
        validateResultMapsCount(rsw, resultMapCount);

        // eg1: rsw不为空 resultMapCount=1 resultSetCount=0
        /** 第三步：处理结果集 */
        while (rsw != null && resultMapCount > resultSetCount) {
            // eg1: ResultMap resultMap=resultMaps.get(0);
            ResultMap resultMap = resultMaps.get(resultSetCount);

            /** 处理结果集, 存储在multipleResults中 */
            handleResultSet(rsw, resultMap, multipleResults, null);

            // eg1: rsw=null
            rsw = getNextResultSet(stmt);

            cleanUpAfterHandlingResultSet();
            resultSetCount++; // eg1: 自增后resultSetCount=1
        }

        String[] resultSets = mappedStatement.getResultSets();
        // eg1: resultSets = null
        if (resultSets != null) {
            while (rsw != null && resultSetCount < resultSets.length) {
                ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
                if (parentMapping != null) {
                    String nestedResultMapId = parentMapping.getNestedResultMapId();
                    ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
                    handleResultSet(rsw, resultMap, null, parentMapping);
                }
                rsw = getNextResultSet(stmt);
                cleanUpAfterHandlingResultSet();
                resultSetCount++;
            }
        }

        // eg1: multipleResults.get(0).get(0) = User{id=2, name='muse2', age=24, userContacts=null}
        /** 返回结果 */
        return collapseSingleResultList(multipleResults);
    }

    @Override
    public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

        ResultSetWrapper rsw = getFirstResultSet(stmt);

        List<ResultMap> resultMaps = mappedStatement.getResultMaps();

        int resultMapCount = resultMaps.size();

        /** 如果rsw != null && resultMapCount < 1，则抛出异常ExecutorException */
        validateResultMapsCount(rsw, resultMapCount);

        /** 不可以是多结果集，否则抛出异常ExecutorException */
        if (resultMapCount != 1) {
            throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
        }

        ResultMap resultMap = resultMaps.get(0);
        return new DefaultCursor<E>(this, resultMap, rsw, rowBounds);
    }

    // eg1: 执行到这里
    private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
        // eg1: rs != null
        /** 通过JDBC获得结果集ResultSet */
        ResultSet rs = stmt.getResultSet();
        while (rs == null) {
            if (stmt.getMoreResults()) {
                rs = stmt.getResultSet();
            } else {
                /**
                 * getUpdateCount()==-1,既不是结果集,又不是更新计数了.说明没的返回了。
                 * 如果getUpdateCount()>=0,则说明当前指针是更新计数(0的时候有可能是DDL指令)。
                 * 无论是返回结果集或是更新计数,那么则可能还继续有其它返回。
                 * 只有在当前指指针getResultSet()==null && getUpdateCount()==-1才说明没有再多的返回。
                 */
                if (stmt.getUpdateCount() == -1) {
                    // no more results. Must be no resultset
                    break;
                }
            }
        }
        // eg1: rs不为空，则将结果集封装到ResultSetWrapper中
        /** 将结果集ResultSet封装到ResultSetWrapper实例中 */
        return rs != null ? new ResultSetWrapper(rs, configuration) : null;
    }

    private ResultSetWrapper getNextResultSet(Statement stmt) throws SQLException {
        // Making this method tolerant of bad JDBC drivers
        try {
            // eg1: stmt.getConnection().getMetaData().supportsMultipleResultSets()=true
            if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
                /** Crazy Standard JDBC way of determining if there are more results*/
                // eg1: stmt.getMoreResults()=false  stmt.getUpdateCount()=-1
                if (!((!stmt.getMoreResults()) && (stmt.getUpdateCount() == -1))) { // eg1: false，不继续执行
                    ResultSet rs = stmt.getResultSet();
                    return rs != null ? new ResultSetWrapper(rs, configuration) : null;
                }
            }
        } catch (Exception e) {
            // Intentionally ignored.
        }
        // eg1: 返回null
        return null;
    }

    private void closeResultSet(ResultSet rs) {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            // ignore
        }
    }

    private void cleanUpAfterHandlingResultSet() {
        nestedResultObjects.clear();
    }

    private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
        if (rsw != null && resultMapCount < 1) {
            throw new ExecutorException(
                    "A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
                            + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
        }
    }

    // eg1: parentMapping = null
    /**
     * 处理结果集
     */
    private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults,
                                 ResultMapping parentMapping) throws SQLException {
        try {
            // eg1: parentMapping = null
            if (parentMapping != null) {
                handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
            } else {
                // eg1: resultHandler = null
                if (resultHandler == null) {
                    // eg1: objectFactory = DefaultObjectFactory defaultResultHandler里面包含了一个空集合的ArrayList实例
                    /** 初始化ResultHandler实例，用于解析查询结果并存储于该实例对象中 */
                    DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
                    /** 解析行数据 */
                    handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
                    multipleResults.add(defaultResultHandler.getResultList());
                } else {
                    handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
                }
            }
        } finally {
            // eg1：
            /** 关闭ResultSet */
            closeResultSet(rsw.getResultSet());
        }
    }

    // eg1: multipleResults.get(0).get(0) = User{id=2, name='muse2', age=24, userContacts=null}
    @SuppressWarnings("unchecked")
    private List<Object> collapseSingleResultList(List<Object> multipleResults) {
        // eg1: multipleResults.size()=1 multipleResults.get(0).get(0) = User{id=2, name='muse2', age=24, userContacts=null}
        return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults; // eg1: 返回 User{id=2, name='muse2', age=24, userContacts=null}
    }

    // eg1: parentMapping = null
    public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler,
                                RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
        // eg1: resultMap.hasNestedResultMaps()=false
        /** 是否是聚合Nested类型的结果集 */
        if (resultMap.hasNestedResultMaps()) {
            ensureNoRowBounds();
            checkResultHandler();
            handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        } else {
            // eg1: parentMapping = null
            handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        }
    }

    private void ensureNoRowBounds() {
        if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (
                rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
            throw new ExecutorException(
                    "Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
                            + "Use safeRowBoundsEnabled=false setting to bypass this check.");
        }
    }

    protected void checkResultHandler() {
        if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
            throw new ExecutorException(
                    "Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
                            + "Use safeResultHandlerEnabled=false setting to bypass this check "
                            + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
        }
    }

    // eg1: parentMapping = null
    private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap,
                                                   ResultHandler<?> resultHandler, RowBounds rowBounds,
                                                   ResultMapping parentMapping) throws SQLException {
        DefaultResultContext<Object> resultContext = new DefaultResultContext<>();

        // eg1: skipRows里面没做什么事情
        /** 将指针移动到rowBounds.getOffset()指定的行号，即：略过（skip）offset之前的行 */
        skipRows(rsw.getResultSet(), rowBounds);

        // eg1: shouldProcessMoreRows(resultContext, rowBounds) = true    rsw.getResultSet().next() = true
        while (shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
            /** 解析结果集中的鉴别器<discriminate/> */
            ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);

            /** 将数据库操作结果保存到POJO并返回 */
            Object rowValue = getRowValue(rsw, discriminatedResultMap);

            // eg1: rowValue=User{id=2, name='muse2', age=24, userContacts=null}  parentMapping = null
            /** 存储POJO对象到DefaultResultHandler中 */
            storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
        }
    }

    // eg1: rowValue=User{id=2, name='muse2', age=24, userContacts=null}  parentMapping = null
    /**
     * 存储POJO对象到DefaultResultHandler中
     */
    private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext,
                             Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
        // eg1: parentMapping = null
        if (parentMapping != null) {
            linkToParents(rs, parentMapping, rowValue);
        } else {
            // eg1: resultHandler里保存空size的ArrayList rowValue=User{id=2, name='muse2', age=24, userContacts=null}
            /** 将结果存储到DefaultResultHandler中 */
            callResultHandler(resultHandler, resultContext, rowValue);
        }
    }

    // eg1: resultHandler里保存空size的ArrayList rowValue=User{id=2, name='muse2', age=24, userContacts=null}
    /**
     *
     */
    @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
    private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext,
                                   Object rowValue) {
        resultContext.nextResultObject(rowValue);
        // eg1: resultHandler=DefaultResultHandler
        ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
    }

    // eg1:
    /**
     * 判断是否应该获取更多的行
     */
    private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) throws SQLException {
        // eg1: context.isStopped() = false
        //      context.getResultCount() = 0
        //      rowBounds.getLimit() = 2147483647 = Integer.MAX_VALUE
        return !context.isStopped() && context.getResultCount() < rowBounds.getLimit(); // eg1: 返回true
    }

    // eg1:
    /**
     * 将指针移动到rowBounds.getOffset()指定的行号，即：略过（skip）offset之前的行
     *
     * @param rs
     * @param rowBounds
     * @throws SQLException
     */
    private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
        // eg1: rs.getType() = 1003 = ResultSet.TYPE_FORWARD_ONLY
        /**
         * ResultSet.TYPE_FORWARD_ONLY          结果集的游标只能向下滚动
         * ResultSet.TYPE_SCROLL_INSENSITIVE    结果集的游标可以上下移动，当数据库变化时，当前结果集不变。
         * ResultSet.TYPE_SCROLL_SENSITIVE      返回可滚动的结果集，当数据库变化时，当前结果集同步改变
         */
        if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
            /** rowBounds.getOffset()不为0 */
            if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
                /** 将指针移动到此ResultSet对象的给定行编号rowBounds.getOffset()。 */
                rs.absolute(rowBounds.getOffset());
            }
        } else {
            // eg1: rowBounds.getOffset() = 0
            for (int i = 0; i < rowBounds.getOffset(); i++) {
                /** 将指针移动到此ResultSet对象的给定行编号rowBounds.getOffset()。 */
                rs.next();
            }
        }
    }

    /**
     * 将数据库操作结果保存到POJO并返回
     */
    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap) throws SQLException {
        final ResultLoaderMap lazyLoader = new ResultLoaderMap();
        /** 创建空的结果对象 */
        Object rowValue = createResultObject(rsw, resultMap, lazyLoader, null);

        // eg1: rowValue=User{id=null, name='null', age=null, userContacts=null}   hasTypeHandlerForResultObject(rsw, resultMap.getType())=false
        if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            /** 创建rowValue的metaObject */
            final MetaObject metaObject = configuration.newMetaObject(rowValue);

            // eg1: foundValues = useConstructorMappings = false
            boolean foundValues = this.useConstructorMappings;

            // eg1: shouldApplyAutomaticMappings(resultMap, false) = true
            /** 是否应用自动映射 */
            if (shouldApplyAutomaticMappings(resultMap, false)) {
                // eg1: applyAutomaticMappings(rsw, resultMap, metaObject, null)=true
                /**
                 * 将查询出来的值赋值给metaObject中的POJO对象
                 */
                foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, null) || foundValues; // eg1: foundValues=true
            }

            // eg1: foundValues=true
            foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, null) || foundValues;

            // eg1: lazyLoader.size()=0   foundValues=true
            foundValues = lazyLoader.size() > 0 || foundValues;

            // eg1: foundValues=true  configuration.isReturnInstanceForEmptyRow()=false
            /** configuration.isReturnInstanceForEmptyRow() 当返回行的所有列都是空时，MyBatis默认返回null。当开启这个设置时，MyBatis会返回一个空实例。*/
            rowValue = (foundValues || configuration.isReturnInstanceForEmptyRow()) ? rowValue : null;
        }
        return rowValue; // eg1: rowValue=User{id=2, name='muse2', age=24, userContacts=null}
    }

    // eg1: isNested=false
    /**
     * 是否应用自动映射
     */
    private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
        // eg1: resultMap.getAutoMapping()=null
        if (resultMap.getAutoMapping() != null) {
            return resultMap.getAutoMapping();
        } else {
            // eg1: isNested=false
            if (isNested) {
                return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
            } else {
                // eg1: configuration.getAutoMappingBehavior()=PARTIAL
                return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior(); // eg1: 返回true
            }
        }
    }


    // eg1: columnPrefix=null
    private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject,
                                          ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
        // eg1: mappedColumnNames={} columnPrefix=null
        final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        boolean foundValues = false;
        final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
        // eg1: propertyMappings={}
        for (ResultMapping propertyMapping : propertyMappings) {
            String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
            if (propertyMapping.getNestedResultMapId() != null) {
                // the user added a column attribute to a nested result map, ignore it
                column = null;
            }
            if (propertyMapping.isCompositeResult()
                    || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)))
                    || propertyMapping.getResultSet() != null) {
                Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader,
                        columnPrefix);
                final String property = propertyMapping.getProperty();
                if (property == null) {
                    continue;
                } else if (value == DEFERED) {
                    foundValues = true;
                    continue;
                }
                if (value != null) {
                    foundValues = true;
                }
                if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property)
                        .isPrimitive())) {
                    // gcode issue #377, call setter on nulls (value is not 'found')
                    metaObject.setValue(property, value);
                }
            }
        }
        return foundValues; // eg1: foundValues=false
    }

    private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping,
                                           ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        if (propertyMapping.getNestedQueryId() != null) {
            return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
        } else if (propertyMapping.getResultSet() != null) {
            addPendingChildRelation(rs, metaResultObject, propertyMapping);   // TODO is that OK?
            return DEFERED;
        } else {
            final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
            final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
            return typeHandler.getResult(rs, column);
        }
    }

    // eg1: columnPrefix=null
    /**
     * 创建自动映射
     */
    private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap,
                                                                    MetaObject metaObject, String columnPrefix) throws SQLException {
        // eg1: mapKey="mapper.UserMapper.getUserById-Inline:null"
        final String mapKey = resultMap.getId() + ":" + columnPrefix;

        // eg1: autoMapping=null
        List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
        if (autoMapping == null) {
            autoMapping = new ArrayList<>();

            // eg1: columnPrefix=null  unMappedColumnNames={"id", "name", "age"}
            final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
            for (String columnName : unmappedColumnNames) {
                // eg1: columnName="id"
                // eg1: columnName="name"
                // eg1: columnName="age"
                String propertyName = columnName;
                // eg1: columnPrefix=null
                if (columnPrefix != null && !columnPrefix.isEmpty()) {
                    if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                        propertyName = columnName.substring(columnPrefix.length());
                    } else {
                        continue;
                    }
                }
                // eg1: configuration.isMapUnderscoreToCamelCase()=true
                // eg1: property="id"
                // eg1: property="name"
                // eg1: property="age"
                final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
                // eg1: property="id" metaObject.hasSetter(property)=true
                // eg1: property="name" metaObject.hasSetter(property)=true
                // eg1: property="age" metaObject.hasSetter(property)=true
                if (property != null && metaObject.hasSetter(property)) {
                    // eg1: resultMap.getMappedProperties().size=0
                    if (resultMap.getMappedProperties().contains(property)) {
                        continue;
                    }
                    // eg1: propertyType=java.lang.Long.class
                    // eg1: propertyType=java.lang.String.class
                    // eg1: propertyType=java.lang.Integer.class
                    final Class<?> propertyType = metaObject.getSetterType(property);
                    // eg1: rsw.getJdbcType(columnName)=BIGINT  columnName="id"
                    // eg1: rsw.getJdbcType(columnName)=VARCHAR  columnName="name"
                    // eg1: rsw.getJdbcType(columnName)=INTEGER  columnName="age"
                    if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
                        // eg1: typeHandler=LongTypeHandler
                        // eg1: typeHandler=StringTypeHandler
                        // eg1: typeHandler=IntegerTypeHandler
                        final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
                        autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
                    } else {
                        configuration.getAutoMappingUnknownColumnBehavior().doAction(mappedStatement, columnName, property, propertyType);
                    }
                } else {
                    configuration.getAutoMappingUnknownColumnBehavior()
                            .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
                }
            }

            // eg1: mapKey="mapper.UserMapper.getUserById-Inline:null"
            autoMappingsCache.put(mapKey, autoMapping);
        }

        // eg1: autoMapping={UnMappedColumnAutoMapping("id", "id", LongTypeHandler@2397, false),
        //                   UnMappedColumnAutoMapping("name", "name", StringTypeHandler@2418, false),
        //                   UnMappedColumnAutoMapping("age", "age", IntegerTypeHandler@2433, false)}
        return autoMapping;
    }

    // eg1: columnPrefix=null
    private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject,
                                           String columnPrefix) throws SQLException {


        /** 创建自动映射 */
        List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
        boolean foundValues = false;

        // eg1: autoMapping={UnMappedColumnAutoMapping("id", "id", LongTypeHandler@2397, false),
        //                   UnMappedColumnAutoMapping("name", "name", StringTypeHandler@2418, false),
        //                   UnMappedColumnAutoMapping("age", "age", IntegerTypeHandler@2433, false)}
        if (autoMapping.size() > 0) {
            for (UnMappedColumnAutoMapping mapping : autoMapping) {
                // eg1: mapping.column="id"      mapping.typeHandler=LongTypeHandler       value=2L
                // eg1: mapping.column="name"    mapping.typeHandler=StringTypeHandler     value="muse2"
                // eg1: mapping.column="age"     mapping.typeHandler=IntegerTypeHandler    value=24
                final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
                if (value != null) {
                    // eg1: foundValues = true
                    // eg1: foundValues = true
                    // eg1: foundValues = true
                    foundValues = true;
                }
                // eg1: configuration.isCallSettersOnNulls()=false  mapping.primitive=false
                // eg1: configuration.isCallSettersOnNulls()=false  mapping.primitive=false
                // eg1: configuration.isCallSettersOnNulls()=false  mapping.primitive=false
                if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
                    // eg1: mapping.property="id"  value=2L
                    // eg1: mapping.property="name"  value="muse2"
                    // eg1: mapping.property="age"  value=24
                    metaObject.setValue(mapping.property, value);
                }
            }
        }
        return foundValues; // eg1: 返回true
    }

    // MULTIPLE RESULT SETS

    private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
        CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(),
                parentMapping.getForeignColumn());
        List<PendingRelation> parents = pendingRelations.get(parentKey);
        if (parents != null) {
            for (PendingRelation parent : parents) {
                if (parent != null && rowValue != null) {
                    linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
                }
            }
        }
    }

    private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping)
            throws SQLException {
        CacheKey cacheKey =
                createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
        PendingRelation deferLoad = new PendingRelation();
        deferLoad.metaObject = metaResultObject;
        deferLoad.propertyMapping = parentMapping;
        List<PendingRelation> relations = pendingRelations.get(cacheKey);
        // issue #255
        if (relations == null) {
            relations = new ArrayList<DefaultResultSetHandler.PendingRelation>();
            pendingRelations.put(cacheKey, relations);
        }
        relations.add(deferLoad);
        ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
        if (previous == null) {
            nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
        } else {
            if (!previous.equals(parentMapping)) {
                throw new ExecutorException("Two different properties are mapped to the same resultSet");
            }
        }
    }

    private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names,
                                                 String columns) throws SQLException {
        CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMapping);
        if (columns != null && names != null) {
            String[] columnsArray = columns.split(",");
            String[] namesArray = names.split(",");
            for (int i = 0; i < columnsArray.length; i++) {
                Object value = rs.getString(columnsArray[i]);
                if (value != null) {
                    cacheKey.update(namesArray[i]);
                    cacheKey.update(value);
                }
            }
        }
        return cacheKey;
    }


    // eg1: columnPrefix = null
    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader,
                                      String columnPrefix) throws SQLException {
        this.useConstructorMappings = false; // reset previous mapping result
        final List<Class<?>> constructorArgTypes = new ArrayList<>();
        final List<Object> constructorArgs = new ArrayList<>();

        /** 创建一个空的resultMap.getType()类型的实例对象 */
        Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);

        // eg1: resultObject=User{id=null, name='null', age=null, userContacts=null}
        //      hasTypeHandlerForResultObject(rsw, resultMap.getType()) = false
        /** 判断resultMap.getType()是否存在TypeHandler */
        if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();

            // eg1: propertyMappings={}
            for (ResultMapping propertyMapping : propertyMappings) {
                /** 如果是聚合查询并且配置了懒加载 */
                if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
                    resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration,
                            objectFactory, constructorArgTypes, constructorArgs);
                    break;
                }
            }
        }
        // eg1: resultObject=User{id=null, name='null', age=null, userContacts=null} constructorArgTypes.isEmpty()=true
        this.useConstructorMappings = (resultObject != null && !constructorArgTypes.isEmpty()); // eg1: useConstructorMappings = false

        return resultObject; // eg1: resultObject=User{id=null, name='null', age=null, userContacts=null}
    }

    // eg1: constructorArgTypes={} constructorArgs={} columnPrefix=null
    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes,
                                      List<Object> constructorArgs, String columnPrefix) throws SQLException {
        // eg1: resultType = vo.User.class
        /** 获得需要转换为POJO的类型 */
        final Class<?> resultType = resultMap.getType();

        /** 将POJO类型包装成MetaClass */
        final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);

        // eg1: constructorMappings = {}
        final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();

        // eg1: hasTypeHandlerForResultObject(rsw, resultType) = false
        /** 判断resultType是否存在TypeHandler */
        if (hasTypeHandlerForResultObject(rsw, resultType)) {
            /** 创建原始的ResultObject，即：通过配置好的TypeHandler来创建 */
            return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
        }

        // eg1: constructorMappings.isEmpty()=true
        else if (!constructorMappings.isEmpty()) {
            /** 创建参数化的ResultObject */
            return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
        }

        // eg1: resultType.isInterface()=false  metaType.hasDefaultConstructor()=true
        else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
            // eg1: objectFactory=DefaultObjectFactory  resultType=vo.User.class
            /** 使用objectFactory初始化User对象*/
            return objectFactory.create(resultType); // eg1: 返回User{id=null, name='null', age=null, userContacts=null}
        }

        else if (shouldApplyAutomaticMappings(resultMap, false)) {
            return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix);
        }
        throw new ExecutorException("Do not know how to create an instance of " + resultType);
    }

    /**
     * 创建参数化的ResultObject
     */
    Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType,
                                           List<ResultMapping> constructorMappings,
                                           List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
                                           String columnPrefix) {
        boolean foundValues = false;
        for (ResultMapping constructorMapping : constructorMappings) {
            final Class<?> parameterType = constructorMapping.getJavaType();
            final String column = constructorMapping.getColumn();
            final Object value;
            try {
                if (constructorMapping.getNestedQueryId() != null) {
                    value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
                } else if (constructorMapping.getNestedResultMapId() != null) {
                    final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
                    value = getRowValue(rsw, resultMap);
                } else {
                    final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
                    value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
                }
            } catch (ResultMapException e) {
                throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
            } catch (SQLException e) {
                throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
            }
            constructorArgTypes.add(parameterType);
            constructorArgs.add(value);
            foundValues = value != null || foundValues;
        }
        return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
    }

    private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType,
                                                List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
                                                String columnPrefix) throws SQLException {
        final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
        final Constructor<?> annotatedConstructor = findAnnotatedConstructor(constructors);
        if (annotatedConstructor != null) {
            return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix,
                    annotatedConstructor);
        } else {
            for (Constructor<?> constructor : constructors) {
                if (allowedConstructor(constructor, rsw.getClassNames())) {
                    return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix,
                            constructor);
                }
            }
        }
        throw new ExecutorException(
                "No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
    }

    private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes,
                                          List<Object> constructorArgs, String columnPrefix, Constructor<?> constructor)
            throws SQLException {
        boolean foundValues = false;
        for (int i = 0; i < constructor.getParameterTypes().length; i++) {
            Class<?> parameterType = constructor.getParameterTypes()[i];
            String columnName = rsw.getColumnNames().get(i);
            TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
            Object value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(columnName, columnPrefix));
            constructorArgTypes.add(parameterType);
            constructorArgs.add(value);
            foundValues = value != null || foundValues;
        }
        return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
    }

    private Constructor<?> findAnnotatedConstructor(final Constructor<?>[] constructors) {
        for (final Constructor<?> constructor : constructors) {
            if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
                return constructor;
            }
        }
        return null;
    }

    private boolean allowedConstructor(final Constructor<?> constructor, final List<String> classNames) {
        final Class<?>[] parameterTypes = constructor.getParameterTypes();
      if (typeNames(parameterTypes).equals(classNames)) {
        return true;
      }
      if (parameterTypes.length != classNames.size()) {
        return false;
      }
        for (int i = 0; i < parameterTypes.length; i++) {
            final Class<?> parameterType = parameterTypes[i];
            if (parameterType.isPrimitive() && !primitiveTypes.getWrapper(parameterType).getName()
                    .equals(classNames.get(i))) {
                return false;
            } else if (!parameterType.isPrimitive() && !parameterType.getName().equals(classNames.get(i))) {
                return false;
            }
        }
        return true;
    }

    private List<String> typeNames(Class<?>[] parameterTypes) {
        List<String> names = new ArrayList<String>();
        for (Class<?> type : parameterTypes) {
            names.add(type.getName());
        }
        return names;
    }

    /**
     * 创建原始的ResultObject，即：通过配置好的TypeHandler来创建
     */
    private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix)
            throws SQLException {
        final Class<?> resultType = resultMap.getType();
        final String columnName;
        if (!resultMap.getResultMappings().isEmpty()) {
            final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
            final ResultMapping mapping = resultMappingList.get(0);
            columnName = prependPrefix(mapping.getColumn(), columnPrefix);
        } else {
            columnName = rsw.getColumnNames().get(0);
        }
        final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
        return typeHandler.getResult(rsw.getResultSet(), columnName);
    }

    //
    // NESTED QUERY
    //

    private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix)
            throws SQLException {
        final String nestedQueryId = constructorMapping.getNestedQueryId();
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        final Object nestedQueryParameterObject =
                prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
        Object value = null;
        if (nestedQueryParameterObject != null) {
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key =
                    executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            final Class<?> targetType = constructorMapping.getJavaType();
            final ResultLoader resultLoader =
                    new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key,
                            nestedBoundSql);
            value = resultLoader.loadResult();
        }
        return value;
    }

    private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping,
                                              ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        final String nestedQueryId = propertyMapping.getNestedQueryId();
        final String property = propertyMapping.getProperty();
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        final Object nestedQueryParameterObject =
                prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
        Object value = null;
        if (nestedQueryParameterObject != null) {
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key =
                    executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            final Class<?> targetType = propertyMapping.getJavaType();
            if (executor.isCached(nestedQuery, key)) {
                executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
                value = DEFERED;
            } else {
                final ResultLoader resultLoader =
                        new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType,
                                key, nestedBoundSql);
                if (propertyMapping.isLazy()) {
                    lazyLoader.addLoader(property, metaResultObject, resultLoader);
                    value = DEFERED;
                } else {
                    value = resultLoader.loadResult();
                }
            }
        }
        return value;
    }

    private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType,
                                                  String columnPrefix) throws SQLException {
        if (resultMapping.isCompositeResult()) {
            return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        } else {
            return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        }
    }

    private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType,
                                             String columnPrefix) throws SQLException {
        final TypeHandler<?> typeHandler;
        if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
            typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
        } else {
            typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
        }
        return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType,
                                                String columnPrefix) throws SQLException {
        final Object parameterObject = instantiateParameterObject(parameterType);
        final MetaObject metaObject = configuration.newMetaObject(parameterObject);
        boolean foundValues = false;
        for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
            final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
            final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
            final Object propValue =
                    typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
            // issue #353 & #560 do not execute nested query if key is null
            if (propValue != null) {
                metaObject.setValue(innerResultMapping.getProperty(), propValue);
                foundValues = true;
            }
        }
        return foundValues ? parameterObject : null;
    }

    private Object instantiateParameterObject(Class<?> parameterType) {
        if (parameterType == null) {
            return new HashMap<Object, Object>();
        } else if (ParamMap.class.equals(parameterType)) {
            return new HashMap<Object, Object>(); // issue #649
        } else {
            return objectFactory.create(parameterType);
        }
    }

    // eg1: columnPrefix=null
    /**
     * 解析结果集中的鉴别器<discriminate/>
     */
    public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
        Set<String> pastDiscriminators = new HashSet<>();
        /** 获得鉴别器 */
        Discriminator discriminator = resultMap.getDiscriminator();
        // eg1: discriminator=null
        while (discriminator != null) {
            final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
            final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
            if (configuration.hasResultMap(discriminatedMapId)) {
                resultMap = configuration.getResultMap(discriminatedMapId);
                Discriminator lastDiscriminator = discriminator;
                discriminator = resultMap.getDiscriminator();
                if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
                    break;
                }
            } else {
                break;
            }
        }
        return resultMap;
    }

    private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
        final ResultMapping resultMapping = discriminator.getResultMapping();
        final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
        return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    private String prependPrefix(String columnName, String prefix) {
        if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
            return columnName;
        }
        return prefix + columnName;
    }

    //
    // HANDLE NESTED RESULT MAPS
    //

    private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap,
                                                   ResultHandler<?> resultHandler, RowBounds rowBounds,
                                                   ResultMapping parentMapping) throws SQLException {
        final DefaultResultContext<Object> resultContext = new DefaultResultContext<Object>();
        skipRows(rsw.getResultSet(), rowBounds);
        Object rowValue = previousRowValue;
        while (shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
            final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);
            final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
            Object partialObject = nestedResultObjects.get(rowKey);
            // issue #577 && #542
            if (mappedStatement.isResultOrdered()) {
                if (partialObject == null && rowValue != null) {
                    nestedResultObjects.clear();
                    storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
                }
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
            } else {
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
                if (partialObject == null) {
                    storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
                }
            }
        }
        if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
            storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
            previousRowValue = null;
        } else if (rowValue != null) {
            previousRowValue = rowValue;
        }
    }

    //
    // GET VALUE FROM ROW FOR NESTED RESULT MAP
    //

    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix,
                               Object partialObject) throws SQLException {
        final String resultMapId = resultMap.getId();
        Object rowValue = partialObject;
        if (rowValue != null) {
            final MetaObject metaObject = configuration.newMetaObject(rowValue);
            putAncestor(rowValue, resultMapId, columnPrefix);
            applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
            ancestorObjects.remove(resultMapId);
        } else {
            final ResultLoaderMap lazyLoader = new ResultLoaderMap();
            rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
            if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
                final MetaObject metaObject = configuration.newMetaObject(rowValue);
                boolean foundValues = this.useConstructorMappings;
                if (shouldApplyAutomaticMappings(resultMap, true)) {
                    foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
                }
                foundValues =
                        applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
                putAncestor(rowValue, resultMapId, columnPrefix);
                foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true)
                        || foundValues;
                ancestorObjects.remove(resultMapId);
                foundValues = lazyLoader.size() > 0 || foundValues;
                rowValue = (foundValues || configuration.isReturnInstanceForEmptyRow()) ? rowValue : null;
            }
            if (combinedKey != CacheKey.NULL_CACHE_KEY) {
                nestedResultObjects.put(combinedKey, rowValue);
            }
        }
        return rowValue;
    }

    private void putAncestor(Object resultObject, String resultMapId, String columnPrefix) {
        ancestorObjects.put(resultMapId, resultObject);
    }

    //
    // NESTED RESULT MAP (JOIN MAPPING)
    //

    private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject,
                                              String parentPrefix, CacheKey parentRowKey, boolean newObject) {
        boolean foundValues = false;
        for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
            final String nestedResultMapId = resultMapping.getNestedResultMapId();
            if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
                try {
                    final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
                    final ResultMap nestedResultMap =
                            getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
                    if (resultMapping.getColumnPrefix() == null) {
                        // try to fill circular reference only when columnPrefix
                        // is not specified for the nested result map (issue #215)
                        Object ancestorObject = ancestorObjects.get(nestedResultMapId);
                        if (ancestorObject != null) {
                            if (newObject) {
                                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
                            }
                            continue;
                        }
                    }
                    final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
                    final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
                    Object rowValue = nestedResultObjects.get(combinedKey);
                    boolean knownValue = (rowValue != null);
                    instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
                    if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
                        rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
                        if (rowValue != null && !knownValue) {
                            linkObjects(metaObject, resultMapping, rowValue);
                            foundValues = true;
                        }
                    }
                } catch (SQLException e) {
                    throw new ExecutorException(
                            "Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: "
                                    + e, e);
                }
            }
        }
        return foundValues;
    }

    private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
        final StringBuilder columnPrefixBuilder = new StringBuilder();
        if (parentPrefix != null) {
            columnPrefixBuilder.append(parentPrefix);
        }
        if (resultMapping.getColumnPrefix() != null) {
            columnPrefixBuilder.append(resultMapping.getColumnPrefix());
        }
        return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
    }

    private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw)
            throws SQLException {
        Set<String> notNullColumns = resultMapping.getNotNullColumns();
        if (notNullColumns != null && !notNullColumns.isEmpty()) {
            ResultSet rs = rsw.getResultSet();
            for (String column : notNullColumns) {
                rs.getObject(prependPrefix(column, columnPrefix));
                if (!rs.wasNull()) {
                    return true;
                }
            }
            return false;
        } else if (columnPrefix != null) {
            for (String columnName : rsw.getColumnNames()) {
                if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix)
            throws SQLException {
        ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
        return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
    }

    //
    // UNIQUE RESULT KEY
    //

    private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
        final CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMap.getId());
        List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
        if (resultMappings.size() == 0) {
            if (Map.class.isAssignableFrom(resultMap.getType())) {
                createRowKeyForMap(rsw, cacheKey);
            } else {
                createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
            }
        } else {
            createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
        }
        if (cacheKey.getUpdateCount() < 2) {
            return CacheKey.NULL_CACHE_KEY;
        }
        return cacheKey;
    }

    private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
        if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
            CacheKey combinedKey;
            try {
                combinedKey = rowKey.clone();
            } catch (CloneNotSupportedException e) {
                throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
            }
            combinedKey.update(parentRowKey);
            return combinedKey;
        }
        return CacheKey.NULL_CACHE_KEY;
    }

    private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
        List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
        if (resultMappings.size() == 0) {
            resultMappings = resultMap.getPropertyResultMappings();
        }
        return resultMappings;
    }

    private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey,
                                                 List<ResultMapping> resultMappings, String columnPrefix)
            throws SQLException {
        for (ResultMapping resultMapping : resultMappings) {
            if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) {
                // Issue #392
                final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
                createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey,
                        nestedResultMap.getConstructorResultMappings(),
                        prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
            } else if (resultMapping.getNestedQueryId() == null) {
                final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
                final TypeHandler<?> th = resultMapping.getTypeHandler();
                List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
                // Issue #114
                if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
                    final Object value = th.getResult(rsw.getResultSet(), column);
                    if (value != null || configuration.isReturnInstanceForEmptyRow()) {
                        cacheKey.update(column);
                        cacheKey.update(value);
                    }
                }
            }
        }
    }

    private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey,
                                                   String columnPrefix) throws SQLException {
        final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
        List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
        for (String column : unmappedColumnNames) {
            String property = column;
            if (columnPrefix != null && !columnPrefix.isEmpty()) {
                // When columnPrefix is specified, ignore columns without the prefix.
                if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                    property = column.substring(columnPrefix.length());
                } else {
                    continue;
                }
            }
            if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
                String value = rsw.getResultSet().getString(column);
                if (value != null) {
                    cacheKey.update(column);
                    cacheKey.update(value);
                }
            }
        }
    }

    private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
        List<String> columnNames = rsw.getColumnNames();
        for (String columnName : columnNames) {
            final String value = rsw.getResultSet().getString(columnName);
            if (value != null) {
                cacheKey.update(columnName);
                cacheKey.update(value);
            }
        }
    }

    private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
        final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
        if (collectionProperty != null) {
            final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
            targetMetaObject.add(rowValue);
        } else {
            metaObject.setValue(resultMapping.getProperty(), rowValue);
        }
    }

    private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
        final String propertyName = resultMapping.getProperty();
        Object propertyValue = metaObject.getValue(propertyName);
        if (propertyValue == null) {
            Class<?> type = resultMapping.getJavaType();
            if (type == null) {
                type = metaObject.getSetterType(propertyName);
            }
            try {
                if (objectFactory.isCollection(type)) {
                    propertyValue = objectFactory.create(type);
                    metaObject.setValue(propertyName, propertyValue);
                    return propertyValue;
                }
            } catch (Exception e) {
                throw new ExecutorException(
                        "Error instantiating collection property for result '" + resultMapping.getProperty()
                                + "'.  Cause: " + e, e);
            }
        } else if (objectFactory.isCollection(propertyValue.getClass())) {
            return propertyValue;
        }
        return null;
    }

    // eg1: resultType = vo.User.class
    /**
     * 判断resultType是否存在TypeHandler
     */
    private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
        // eg1: rsw.getColumnNames().size() = 3
        if (rsw.getColumnNames().size() == 1) {
            return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
        }
        return typeHandlerRegistry.hasTypeHandler(resultType);
    }

}
