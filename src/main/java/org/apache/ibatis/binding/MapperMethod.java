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
package org.apache.ibatis.binding;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * 通过SqlCommandType来映射跳转到需要执行的方法
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */
public class MapperMethod {

    // 记录了SQL语句的名称和类型
    private final SqlCommand command;

    // Mapper接口中对应方法的相关信息
    private final MethodSignature method;

    // eg1: mapperInterface = interface mapper.UserMapper
    //      method = public abstract vo.User mapper.UserMapper.getUserById(java.lang.Long)
    public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
        this.command = new SqlCommand(config, mapperInterface, method);
        this.method = new MethodSignature(config, mapperInterface, method);
    }

    /**
     * 必看：MapperMethod采用命令模式运行，根据上下文跳转，它可能跳转到许多方法中。实际上它最后就是通过SqlSession对象去运行对象的SQL。
     */
    // eg1: sqlSession = DefaultSqlSession@1953  args = {2L}
    public Object execute(SqlSession sqlSession, Object[] args) {
        Object result;
        // eg1: command.getType() = SELECT
        switch (command.getType()) {
            case INSERT: {
                Object param = method.convertArgsToSqlCommandParam(args);
                result = rowCountResult(sqlSession.insert(command.getName(), param));
                break;
            }
            case UPDATE: {
                Object param = method.convertArgsToSqlCommandParam(args);
                result = rowCountResult(sqlSession.update(command.getName(), param));
                break;
            }
            case DELETE: {
                Object param = method.convertArgsToSqlCommandParam(args);
                result = rowCountResult(sqlSession.delete(command.getName(), param));
                break;
            }
            case SELECT:
                // eg1: method.returnsVoid() = false  method.hasResultHandler() = false
                if (method.returnsVoid() && method.hasResultHandler()) {
                    executeWithResultHandler(sqlSession, args);
                    result = null;
                } else if (method.returnsMany()) { // eg1: method.returnsMany() = false
                    result = executeForMany(sqlSession, args);
                } else if (method.returnsMap()) { // eg1: method.returnsMap() = false
                    result = executeForMap(sqlSession, args);
                } else if (method.returnsCursor()) { // eg1: method.returnsCursor() = false
                    result = executeForCursor(sqlSession, args);
                } else {
                    // eg1: args = {2L}
                    /** 将参数转换为sql语句需要的入参 */
                    Object param = method.convertArgsToSqlCommandParam(args);

                    // eg1: sqlSession=DefaultSqlSession  command.getName()="mapper.UserMapper.getUserById" param={"id":2L, "param1":2L}
                    /** 执行sql查询操作 */
                    result = sqlSession.selectOne(command.getName(), param);
                }
                break;
            case FLUSH:
                result = sqlSession.flushStatements();
                break;
            default:
                throw new BindingException("Unknown execution method for: " + command.getName());
        }
        if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
            throw new BindingException("Mapper method '" + command.getName()
                    + " attempted to return null from a method with a primitive return type (" + method.getReturnType()
                    + ").");
        }
        return result;
    }

    private Object rowCountResult(int rowCount) {
        final Object result;
        if (method.returnsVoid()) {
            result = null;
        } else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
            result = rowCount;
        } else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
            result = (long) rowCount;
        } else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
            result = rowCount > 0;
        } else {
            throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: " + method.getReturnType());
        }
        return result;
    }

    private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
        MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
        if (void.class.equals(ms.getResultMaps().get(0).getType())) {
            throw new BindingException("method " + command.getName()
                    + " needs either a @ResultMap annotation, a @ResultType annotation,"
                    + " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
        }
        Object param = method.convertArgsToSqlCommandParam(args);
        if (method.hasRowBounds()) {
            RowBounds rowBounds = method.extractRowBounds(args);
            sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
        } else {
            sqlSession.select(command.getName(), param, method.extractResultHandler(args));
        }
    }

    private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
        List<E> result;
        Object param = method.convertArgsToSqlCommandParam(args);
        if (method.hasRowBounds()) {
            RowBounds rowBounds = method.extractRowBounds(args);
            result = sqlSession.<E>selectList(command.getName(), param, rowBounds);
        } else {
            result = sqlSession.<E>selectList(command.getName(), param);
        }
        // issue #510 Collections & arrays support
        if (!method.getReturnType().isAssignableFrom(result.getClass())) {
            if (method.getReturnType().isArray()) {
                return convertToArray(result);
            } else {
                return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
            }
        }
        return result;
    }

    private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
        Cursor<T> result;
        Object param = method.convertArgsToSqlCommandParam(args);
        if (method.hasRowBounds()) {
            RowBounds rowBounds = method.extractRowBounds(args);
            result = sqlSession.<T>selectCursor(command.getName(), param, rowBounds);
        } else {
            result = sqlSession.<T>selectCursor(command.getName(), param);
        }
        return result;
    }

    private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
        Object collection = config.getObjectFactory().create(method.getReturnType());
        MetaObject metaObject = config.newMetaObject(collection);
        metaObject.addAll(list);
        return collection;
    }

    @SuppressWarnings("unchecked")
    private <E> Object convertToArray(List<E> list) {
        Class<?> arrayComponentType = method.getReturnType().getComponentType();
        Object array = Array.newInstance(arrayComponentType, list.size());
        if (arrayComponentType.isPrimitive()) {
            for (int i = 0; i < list.size(); i++) {
                Array.set(array, i, list.get(i));
            }
            return array;
        } else {
            return list.toArray((E[]) array);
        }
    }

    private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
        Map<K, V> result;
        Object param = method.convertArgsToSqlCommandParam(args);
        if (method.hasRowBounds()) {
            RowBounds rowBounds = method.extractRowBounds(args);
            result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey(), rowBounds);
        } else {
            result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey());
        }
        return result;
    }

    public static class ParamMap<V> extends HashMap<String, V> {

        private static final long serialVersionUID = -2212268410512043556L;

        @Override
        public V get(Object key) {
            if (!super.containsKey(key)) {
                throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
            }
            return super.get(key);
        }

    }

    public static class SqlCommand {

        /** MappedStatement的唯一标识id */
        private final String name;

        /** sql的命令类型 UNKNOWN, INSERT, UPDATE, DELETE, SELECT, FLUSH; */
        private final SqlCommandType type;

        // eg1: mapperInterface = interface mapper.UserMapper
        //      method = public abstract vo.User mapper.UserMapper.getUserById(java.lang.Long)
        public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
            // eg1: getUserById
            final String methodName = method.getName();

            // eg1: interface mapper.UserMapper
            final Class<?> declaringClass = method.getDeclaringClass();

            /**
             * String statementId = mapperInterface.getName() + "." + methodName;
             * 尝试通过statementId，从Configuration中获得MappedStatement
             */
            MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass, configuration);

            // eg1: ms不为空
            if (ms == null) {
                if (method.getAnnotation(Flush.class) != null) {
                    name = null;
                    type = SqlCommandType.FLUSH;
                } else {
                    throw new BindingException("Invalid bound statement (not found): "
                            + mapperInterface.getName() + "." + methodName);
                }
            } else {
                name = ms.getId(); // eg1: name = mapper.UserMapper.getUserById
                type = ms.getSqlCommandType(); // eg1: type = SqlCommandType.SELECT
                if (type == SqlCommandType.UNKNOWN) {
                    throw new BindingException("Unknown execution method for: " + name);
                }
            }
        }

        public String getName() {
            return name;
        }

        public SqlCommandType getType() {
            return type;
        }

        // eg1: mapperInterface = interface mapper.UserMapper
        //      methodName = "getUserById"
        //      declaringClass = interface mapper.UserMapper
        /**
         * String statementId = mapperInterface.getName() + "." + methodName;
         * 尝试通过statementId，从Configuration中获得MappedStatement
         */
        private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName,
                                                       Class<?> declaringClass, Configuration configuration) {
            String statementId = mapperInterface.getName() + "." + methodName;
            // eg1: statementId = "mapper.UserMapper.getUserById"
            if (configuration.hasStatement(statementId)) {
                // eg1: 获得mapper.UserMapper.getUserById对应的MappedStatement实例
                return configuration.getMappedStatement(statementId);
            } else if (mapperInterface.equals(declaringClass)) {
                return null;
            }
            /**
             * 如果没有找到mapperInterface对应的MappedStatement，
             * 那么则遍历mapperInterface的接口，
             * 查询这些接口是否有对应的MappedStatement，如果有，则返回。
             */
            for (Class<?> superInterface : mapperInterface.getInterfaces()) {
                if (declaringClass.isAssignableFrom(superInterface)) {
                    MappedStatement ms = resolveMappedStatement(superInterface, methodName,
                            declaringClass, configuration);
                    if (ms != null) {
                        return ms;
                    }
                }
            }
            return null;
        }
    }

    public static class MethodSignature {

        private final boolean returnsMany;                  // 判断返回类型是集合或者数组吗
        private final boolean returnsMap;                   // 判断返回类型是Map类型吗
        private final boolean returnsVoid;                  // 判断返回类型是集void吗
        private final boolean returnsCursor;                // 判断返回类型是Cursor类型吗
        private final Class<?> returnType;                  // 方法返回类型
        private final String mapKey;                        // 获得@MapKey注解里面的value值
        private final Integer resultHandlerIndex;           // 入参为ResultHandler类型的下标号
        private final Integer rowBoundsIndex;               // 入参为RowBounds类型的下标号
        private final ParamNameResolver paramNameResolver;  // 入参名称解析器

        // eg1: mapperInterface = interface mapper.UserMapper
        //      method = public abstract vo.User mapper.UserMapper.getUserById(java.lang.Long)
        public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
            Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
            // eg1: resolvedReturnType = vo.User.class
            if (resolvedReturnType instanceof Class<?>) {
                // eg1: returnType = resolvedReturnType = class vo.User
                this.returnType = (Class<?>) resolvedReturnType;
            } else if (resolvedReturnType instanceof ParameterizedType) {
                this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
            } else {
                this.returnType = method.getReturnType();
            }

            // eg1: returnsVoid = false
            this.returnsVoid = void.class.equals(this.returnType);

            // eg1: returnsMany = false
            /** 判断returnType是集合或者数组吗？ */
            this.returnsMany = (configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray());

            // eg1: returnsCursor = false
            /** 判断returnType是Cursor类型吗？ */
            this.returnsCursor = Cursor.class.equals(this.returnType);

            // eg1: mapKey = null，returnsMap = false
            /** 判断returnType是Map类型吗？ */
            this.mapKey = getMapKey(method);
            this.returnsMap = (this.mapKey != null);

            // eg1：rowBoundsIndex = null
            /** 获得方法method中，入参为RowBounds类型的下标号 */
            this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);

            // eg1：resultHandlerIndex = null
            /** 获得方法method中，入参为ResultHandler类型的下标号 */
            this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);

            // eg1:生成paramNameResolver实例对象 入参名称解析起 eg:@Param("xxx")
            /** 生成paramNameResolver实例对象, 构造方法中已经对参数序号和参数名称进行了映射 */
            this.paramNameResolver = new ParamNameResolver(configuration, method);
        }

        // eg1: args = {2L}
        /**
         * 将参数转换为sql语句需要的入参
         */
        public Object convertArgsToSqlCommandParam(Object[] args) {
            return paramNameResolver.getNamedParams(args);
        }

        public boolean hasRowBounds() {
            return rowBoundsIndex != null;
        }

        public RowBounds extractRowBounds(Object[] args) {
            return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
        }

        public boolean hasResultHandler() {
            return resultHandlerIndex != null;
        }

        public ResultHandler extractResultHandler(Object[] args) {
            return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
        }

        public String getMapKey() {
            return mapKey;
        }

        public Class<?> getReturnType() {
            return returnType;
        }

        public boolean returnsMany() {
            return returnsMany;
        }

        public boolean returnsMap() {
            return returnsMap;
        }

        public boolean returnsVoid() {
            return returnsVoid;
        }

        public boolean returnsCursor() {
            return returnsCursor;
        }


        // eg1: method = public abstract vo.User mapper.UserMapper.getUserById(java.lang.Long)
        //      paramType = RowBounds.class
        /**
         * 获得方法method中，入参类型为paramType的下标号。
         *
         * 如果不包含，则返回null。
         * 如果包含，且唯一，则返回对应的下标号。 如果不唯一，则抛异常。
         */
        private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
            Integer index = null;
            // eg1: argTypes[0] = Long.class
            final Class<?>[] argTypes = method.getParameterTypes();
            for (int i = 0; i < argTypes.length; i++) {
                // eg1: RowBounds.class.isAssignableFrom(Long.class)等于false
                if (paramType.isAssignableFrom(argTypes[i])) {
                    if (index == null) {
                        index = i;
                    } else {
                        throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
                    }
                }
            }
            // eg1: index=null
            return index;
        }

        // eg1: public abstract vo.User mapper.UserMapper.getUserById(java.lang.Long)
        /**
         * 获得@MapKey注解里面的value值。
         */
        private String getMapKey(Method method) {
            String mapKey = null;
            // eg1： method.getReturnType() = class vo.User， 所以返回false
            if (Map.class.isAssignableFrom(method.getReturnType())) {
                final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
                if (mapKeyAnnotation != null) {
                    mapKey = mapKeyAnnotation.value();
                }
            }
            //  eg1： mapKey=null
            return mapKey;
        }
    }

}
