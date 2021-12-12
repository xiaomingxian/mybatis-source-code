/**
 *    Copyright 2009-2017 the original author or authors.
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
package org.apache.ibatis.type;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 获取泛型T的Type对象
 *
 * References a generic type（引用泛型类型）.
 *
 * @param <T> the referenced type
 *
 * @author Simone Tripodi
 * @modify muse
 * @since 3.1.0
 */
public abstract class TypeReference<T> {

    private final Type rawType;

    protected TypeReference() {
        rawType = getSuperclassTypeParameter(getClass());
    }

    /**
     * 【getSuperclass 与 getGenericSuperclass的区别】
     * **getSuperclass 返回直接继承的父类（由于编译擦除，没有显示泛型参数），返回Class对象
     * **getGenericSuperclass 返回直接继承的父类（包含泛型参数），返回Type对象
     *
     * public class Test {
     *      public static void main(String[] args) {
     *      --------
     *      }
     * }
     *
     * class Person<T> {}
     * class Student extends Person<Test> {}
     *
     * 输出结果：
     * Student.class.getSuperclass()	class cn.test.Person
     * Student.class.getGenericSuperclass()	cn.test.Person<cn.test.Test>
     * Test.class.getSuperclass()	class java.lang.Object
     * Test.class.getGenericSuperclass()	class java.lang.Object
     * Object.class.getGenericSuperclass()	null
     * Object.class.getSuperclass()	null
     * void.class.getSuperclass()	null
     * void.class.getGenericSuperclass()	null
     * int[].class.getSuperclass()	class java.lang.Object
     * int[].class.getGenericSuperclass()	class java.lang.Object
     * getSuperclassTypeParameter 返回Test
     *
     * @param clazz
     * @return
     */
    Type getSuperclassTypeParameter(Class<?> clazz) {
        // getGenericSuperclass 返回直接继承的父类（包含泛型参数）
        Type genericSuperclass = clazz.getGenericSuperclass();
        if (genericSuperclass instanceof Class) {
            if (TypeReference.class != genericSuperclass) {
                // getSuperclass 返回直接继承的父类（由于编译擦除，没有显示泛型参数）
                return getSuperclassTypeParameter(clazz.getSuperclass());
            }

            throw new TypeException("'" + getClass() + "' extends TypeReference but misses the type parameter. "
                    + "Remove the extension or add a type parameter to it.");
        }

        // 从一个泛型类型中获取第一个泛型参数的类型类
        Type rawType = ((ParameterizedType) genericSuperclass).getActualTypeArguments()[0];
        // TODO remove this when Reflector is fixed to return Types
        if (rawType instanceof ParameterizedType) {
            rawType = ((ParameterizedType) rawType).getRawType();
        }

        return rawType;
    }

    public final Type getRawType() {
        return rawType;
    }

    @Override
    public String toString() {
        return rawType.toString();
    }

}
