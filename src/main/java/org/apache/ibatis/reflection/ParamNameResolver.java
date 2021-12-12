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
package org.apache.ibatis.reflection;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

public class ParamNameResolver {

    private static final String GENERIC_NAME_PREFIX = "param";

    /**
     * <p>
     * The key is the index and the value is the name of the parameter.<br />
     * The name is obtained from {@link Param} if specified. When {@link Param} is not specified,
     * the parameter index is used. Note that this index could be different from the actual index
     * when the method has special parameters (i.e. {@link RowBounds} or {@link ResultHandler}).
     * </p>
     * <ul>
     * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
     * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
     * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
     * </ul>
     */
    /** key:下标  value:对应参数名称*/
    private final SortedMap<Integer, String> names;

    /** 参数是否用@Param注解修饰*/
    private boolean hasParamAnnotation;

    // eg1: method = public abstract vo.User mapper.UserMapper.getUserById(java.lang.Long)
    /**
     * 解析方法入参，维护到names中。
     */
    public ParamNameResolver(Configuration config, Method method) {
        // eg1: paramTypes[0] = Long.class
        final Class<?>[] paramTypes = method.getParameterTypes();

        // eg1: paramAnnotations[0][0] = @org.apache.ibatis.annotations.Param(value=id)
        /**
         * 首先举个例子:
         * @RedisScan
         * public void save(@RedisSave() int id, @RedisSave() String name){
         *  ... ...
         * }
         *
         * Annotation[][] annos = method.getParameterAnnotations();
         * 二维数组中：第一个参数下标为0，第二参数下标为1
         * 即：annos[0][0]=RedisSave 和 annos[1][0]=RedisSave，也就是说,二维数组是包含多个仅有一个值的数组。
         * 因为参数前可以添加多个注解，所以是二维数组；一个参数上不可以添加相同的注解，同一个注解可以加在不同的参数上。
         */
        final Annotation[][] paramAnnotations = method.getParameterAnnotations();
        final SortedMap<Integer, String> map = new TreeMap<>();
        // eg1: paramCount = 1
        int paramCount = paramAnnotations.length;
        /**
         * get names from @Param annotations
         */
        for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
            // 判断是否是特殊的参数——即：RowBounds.class或ResultHandler.class
            if (isSpecialParameter(paramTypes[paramIndex])) {
                continue;
            }
            String name = null;
            // eg1: paramAnnotations[0] = @org.apache.ibatis.annotations.Param(value=id)
            /** 使用@Param指定的入参名称 */
            for (Annotation annotation : paramAnnotations[paramIndex]) {
                if (annotation instanceof Param) {
                    hasParamAnnotation = true;
                    // eg1: name = "id"
                    name = ((Param) annotation).value();
                    break;
                }
            }
            /** 没有使用@Param指定的入参名称 */
            if (name == null) {
                /** @Param was not specified；useActualParamName默认值为true*/
                if (config.isUseActualParamName()) {
                    /** use the parameter index as the name ("arg0", "arg1", ...) */
                    name = getActualParamName(method, paramIndex);
                }
                if (name == null) {
                    /** use the parameter index as the name ("0", "1", ...) */
                    name = String.valueOf(map.size());
                }
            }
            // eg1: paramIndex=0  name="id"
            map.put(paramIndex, name);
        }
        // eg1: names={0:"id"}
        names = Collections.unmodifiableSortedMap(map);
    }

    private String getActualParamName(Method method, int paramIndex) {
        if (Jdk.parameterExists) {
            return ParamNameUtil.getParamNames(method).get(paramIndex);
        }
        return null;
    }

    // eg1: clazz = Long.class
    /**
     * 判断是否是特殊的参数——即：RowBounds.class或ResultHandler.class
     */
    private static boolean isSpecialParameter(Class<?> clazz) {
        // eg1: RowBounds.class.isAssignableFrom(Long.class) = false
        //      ResultHandler.class.isAssignableFrom(Long.class) = false
        return RowBounds.class.isAssignableFrom(clazz) || ResultHandler.class.isAssignableFrom(clazz);
    }

    /**
     * Returns parameter names referenced by SQL providers.
     */
    public String[] getNames() {
        return names.values().toArray(new String[0]);
    }

    /**
     * 获得属性名与入参值的映射关系
     */
    // eg1:  args = {2L}
    public Object getNamedParams(Object[] args) {
        // eg1: names={0:"id"} paramCount=1
        final int paramCount = names.size();
        if (args == null || paramCount == 0) {
            return null;
        }

        // eg1: hasParamAnnotation=true
        /** 如果不包含@Param注解并且只有一个入参*/
        else if (!hasParamAnnotation && paramCount == 1) {
            return args[names.firstKey()]; // 0 -> "arg0"
        } else {
            final Map<String, Object> param = new ParamMap<>();
            int i = 0;
            // eg1: names={0:"id"}
            for (Map.Entry<Integer, String> entry : names.entrySet()) {
                // eg1: param.put("id"：2L)     entry.getKey()=0  args[0]=2L
                param.put(entry.getValue(), args[entry.getKey()]);

                /**
                 * add generic param names (param1, param2, ...)
                 */
                // eg1: genericParamName = "param1"
                final String genericParamName = GENERIC_NAME_PREFIX + String.valueOf(i + 1);

                /**
                 * ensure not to overwrite parameter named with @Param
                 */
                // eg1: names={0:"id"}  genericParamName = "param1"
                if (!names.containsValue(genericParamName)) {
                    // eg1: param.put("param1", 2L)
                    param.put(genericParamName, args[entry.getKey()]);
                }
                i++;
            }
            // eg1: param={"id": 2L, "param1", 2L}
            return param;
        }
    }
}
