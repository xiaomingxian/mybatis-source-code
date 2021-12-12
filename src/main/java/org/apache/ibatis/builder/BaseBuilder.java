/**
 *    Copyright 2009-2020 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeAliasRegistry;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * 基础构造器
 *
 * @author Clinton Begin
 * @modify muse
 */
public abstract class BaseBuilder {
    // mybatis的配置信息对象
    protected final Configuration configuration;

    // 别名注册器，由HashMap<String, Class<?>>存储相关别名信息
    protected final TypeAliasRegistry typeAliasRegistry;

    // typeHandler注册器，由HashMap<Class<?>, TypeHandler<?>>存储相关信息
    protected final TypeHandlerRegistry typeHandlerRegistry;

    public BaseBuilder(Configuration configuration) {
        this.configuration = configuration;
        this.typeAliasRegistry = this.configuration.getTypeAliasRegistry();
        this.typeHandlerRegistry = this.configuration.getTypeHandlerRegistry();
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    protected Pattern parseExpression(String regex, String defaultValue) {
        return Pattern.compile(regex == null ? defaultValue : regex);
    }

    protected Boolean booleanValueOf(String value, Boolean defaultValue) {
        return value == null ? defaultValue : Boolean.valueOf(value);
    }

    protected Integer integerValueOf(String value, Integer defaultValue) {
        return value == null ? defaultValue : Integer.valueOf(value);
    }

    protected Set<String> stringSetValueOf(String value, String defaultValue) {
        value = (value == null ? defaultValue : value);
        return new HashSet<String>(Arrays.asList(value.split(",")));
    }

    protected JdbcType resolveJdbcType(String alias) {
        if (alias == null) {
            return null;
        }
        try {
            return JdbcType.valueOf(alias);
        } catch (IllegalArgumentException e) {
            throw new BuilderException("Error resolving JdbcType. Cause: " + e, e);
        }
    }

    protected ResultSetType resolveResultSetType(String alias) {
        if (alias == null) {
            return null;
        }
        try {
            return ResultSetType.valueOf(alias);
        } catch (IllegalArgumentException e) {
            throw new BuilderException("Error resolving ResultSetType. Cause: " + e, e);
        }
    }

    protected ParameterMode resolveParameterMode(String alias) {
        if (alias == null) {
            return null;
        }
        try {
            return ParameterMode.valueOf(alias);
        } catch (IllegalArgumentException e) {
            throw new BuilderException("Error resolving ParameterMode. Cause: " + e, e);
        }
    }

    // eg： alias="JAVASSIST"
    protected Object createInstance(String alias) {
        Class<?> clazz = resolveClass(alias);
        if (clazz == null) {
            return null;
        }
        try {
            return resolveClass(alias).newInstance();
        } catch (Exception e) {
            throw new BuilderException("Error creating instance. Cause: " + e, e);
        }
    }

    /**
     * 将alias转化为Class
     *
     * @param alias
     * @return
     */
    // eg： alias="JAVASSIST"
    protected Class<?> resolveClass(String alias) {
        if (alias == null) {
            return null;
        }
        try {
            return resolveAlias(alias);
        } catch (Exception e) {
            throw new BuilderException("Error resolving class. Cause: " + e, e);
        }
    }

    /**
     * 根据typeHander别名字符串，去别名注册器（typeAliasRegistry）中获得TypeHandler类型的Class对象
     * （如果取得的Class不是TypeHandler类型，则抛出运行时异常），然后再通过
     * resolveTypeHandler(Class<?> javaType, Class<? extends TypeHandler<?>> typeHandlerType)方法，获得TypeHandler实例。
     *
     * @param javaType 该参数只用于如果typeHandler中并未注册此javaType，则创建一个TypeHandler对象。并无其他用处。
     * @param typeHandlerAlias typeHandler的别名字符串，要求对应的Class实现了TypeHandler接口或子接口，否则抛出异常
     * @return
     */
    protected TypeHandler<?> resolveTypeHandler(Class<?> javaType, String typeHandlerAlias) {
        if (typeHandlerAlias == null) {
            return null;
        }
        Class<?> type = resolveClass(typeHandlerAlias);
        /**
         * 【isAssignableFrom与instanceof的区别】
         *
         * isAssignableFrom 是用来判断一个类Class1和另一个类Class2是否相同或是Class1是Class2的超类或接口。
         * 通常调用格式是：Class1.isAssignableFrom (Class2)
         * 调用者和参数都是java.lang.Class类型。
         *
         * instanceof 是用来判断一个对象实例(o)是否是一个类或接口的或其子类子接口的实例。
         * 格式是：o instanceof TypeName
         * 第一个参数是对象实例名，第二个参数是具体的类名或接口名
         */
        // 如果是普通类型，则抛出异常。
        if (type != null && !TypeHandler.class.isAssignableFrom(type)) {
            throw new BuilderException("Type " + type.getName()
                    + " is not a valid TypeHandler because it does not implement TypeHandler interface");
        }

        return resolveTypeHandler(javaType, (Class<? extends TypeHandler<?>>) type);
    }

    /**
     * 根据typeHander的Class对象，获得TypeHandler实例，如果typeHandler注册器（typeHandlerRegistry）并未注册此类typeHandler，则创建
     * TypeHandler对象
     *
     * @param javaType
     * @param typeHandlerType
     * @return
     */
    protected TypeHandler<?> resolveTypeHandler(Class<?> javaType, Class<? extends TypeHandler<?>> typeHandlerType) {
        if (typeHandlerType == null) {
            return null;
        }
        // javaType ignored for injected handlers see issue #746 for full detail
        TypeHandler<?> handler = typeHandlerRegistry.getMappingTypeHandler(typeHandlerType);
        if (handler == null) {
            // not in registry, create a new one
            handler = typeHandlerRegistry.getInstance(javaType, typeHandlerType);
        }
        return handler;
    }

    /**
     * 将alias别名转化为Class
     *
     * @param alias
     * @return
     */
    // eg： alias="JAVASSIST"
    protected Class<?> resolveAlias(String alias) {
        return typeAliasRegistry.resolveAlias(alias);
    }
}
