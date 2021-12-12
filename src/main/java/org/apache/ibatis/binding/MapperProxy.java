/**
 * Copyright 2009-2020 the original author or authors.
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

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import org.apache.ibatis.lang.UsesJava7;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.SqlSession;

/**
 * JDK动态代理
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @modify muse
 */
public class MapperProxy<T> implements InvocationHandler, Serializable {

    private static final long serialVersionUID = -6424540398559729838L;

    /** 记录了关联的SqlSession对象 */
    private final SqlSession sqlSession;

    /** Mapper接口对应的Class对象 */
    private final Class<T> mapperInterface;

     /** MapperMethod对象会完成参数转换以及SQL语句的执行功能。需要注意的是，MapperMethod中并不记录任何状态相关的信息，所以可以在多个代理对象之间共享 */
    private final Map<Method, MapperMethod> methodCache;

    public MapperProxy(SqlSession sqlSession, Class<T> mapperInterface, Map<Method, MapperMethod> methodCache) {
        this.sqlSession = sqlSession;
        this.mapperInterface = mapperInterface;
        this.methodCache = methodCache;
    }

    /**
     * 对代理类的所有方法的执行，都会进入到invoke方法中
     *
     * @param proxy
     * @param method
     * @param args
     * @return
     * @throws Throwable
     */
    // eg1: UserMapper userMapper = sqlSession.getMapper(UserMapper.class);
    //      User user = userMapper.getUserById(2L); args = {2L}
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            /** 如果被代理的方法是Object类的方法，如toString()、clone()，则不进行代理 */
            // eg1: method.getDeclaringClass()==interface mapper.UserMapper  由于被代理的方法是UserMapper的getUserById方法，而不是Object的方法，所以返回false
            if (Object.class.equals(method.getDeclaringClass())) {
                return method.invoke(this, args);
            }

            /** 如果是接口中的default方法，则调用default方法 */
            else if (isDefaultMethod(method)) { // eg1: 不是default方法，返回false
                return invokeDefaultMethod(proxy, method, args);
            }
        } catch (Throwable t) {
            throw ExceptionUtil.unwrapThrowable(t);
        }
        // eg1: method = public abstract vo.User mapper.UserMapper.getUserById(java.lang.Long)
        /** 初始化一个MapperMethod并放入缓存中 或者 从缓存中取出之前的MapperMethod */
        final MapperMethod mapperMethod = cachedMapperMethod(method);

        // eg1: sqlSession = DefaultSqlSession@1953  args = {2L}
        /** 调用MapperMethod.execute()方法执行SQL语句 */
        return mapperMethod.execute(sqlSession, args);
    }

    // eg1: public abstract vo.User mapper.UserMapper.getUserById(java.lang.Long)
    private MapperMethod cachedMapperMethod(Method method) {
        /**
         * 在缓存中查找MapperMethod，若没有，则创建MapperMethod对象，并添加到methodCache集合中缓存
         */
        // eg1: 因为methodCache为空，所以mapperMethod等于null
        MapperMethod mapperMethod = methodCache.get(method);
        if (mapperMethod == null) {
            // eg1: 构建mapperMethod对象，并维护到缓存methodCache中
            mapperMethod = new MapperMethod(mapperInterface, method, sqlSession.getConfiguration());
            // eg1: method = public abstract vo.User mapper.UserMapper.getUserById(java.lang.Long)
            methodCache.put(method, mapperMethod);
        }
        return mapperMethod;
    }

    @UsesJava7
    @SuppressWarnings("all")
    private Object invokeDefaultMethod(Object proxy, Method method, Object[] args) throws Throwable {
        final Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class
                .getDeclaredConstructor(Class.class, int.class);
        if (!constructor.isAccessible()) {
            constructor.setAccessible(true);
        }
        final Class<?> declaringClass = method.getDeclaringClass();
        return constructor
                .newInstance(declaringClass,
                        MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED
                                | MethodHandles.Lookup.PACKAGE | MethodHandles.Lookup.PUBLIC)
                .unreflectSpecial(method, declaringClass).bindTo(proxy).invokeWithArguments(args);
    }

    /**
     * 是否是接口中的defalut方法
     *
     * 即：
     * public interface UserMapper {
     *     default void foo() {
     *         System.out.println("InterfaceA foo");
     *     }
     * }
     *
     * 同时满足以下两个条件：
     * 1> public修饰，而不是abstract或static
     * 2> 方法所在的类是接口
     *
     *      ABSTRACT        1024        100 00000000
     *      PUBLIC          1           000 00000001
     *      STATIC          8           000 00001000
     * ---------------------------------------------
     * ABSTRACT | PUBLIC | STATIC       100 00001001
     *
     * 处理步骤：
     * 第一步：method.getModifiers() & (Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC) ——> 清洗modifiers，只保留ABSTRACT，PUBLIC和STATIC这三个Modifier
     * 第二步：清洗后，判断只是Modifier.PUBLIC
     * 第三步：如果只是PUBLIC，那么判断是接口类
     * */
    private boolean isDefaultMethod(Method method) {
        /**
         * method.getModifiers() 获得方法的修饰符
         */
        //eg1: method.getModifiers()=1025=10000000001   10000000001&10000001001=1025=10000000001不等于Modifier.PUBLIC，所以返回false
        return ((method.getModifiers() & (Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC)) == Modifier.PUBLIC)
                && method.getDeclaringClass().isInterface();
    }
}
