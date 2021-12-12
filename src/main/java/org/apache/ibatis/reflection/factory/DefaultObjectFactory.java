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
package org.apache.ibatis.reflection.factory;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.ibatis.reflection.ReflectionException;

/**
 * 当Mybatis在构建一个结果返回的时候，都会使用ObjectFactory来构建POJO
 * 默认的对象工厂
 *
 * @author Clinton Begin
 * @modify muse
 */
public class DefaultObjectFactory implements ObjectFactory, Serializable {

    private static final long serialVersionUID = -8855120656740914948L;

    // eg1: type = List.class
    // eg1: type=User.class
    @Override
    public <T> T create(Class<T> type) {
        return create(type, null, null); // eg1: 生成空集合的ArrayList对象返回
    }

    // eg1: type=List.class  constructorArgTypes=null constructorArgs=null
    // eg1: type=User.class  constructorArgTypes=null constructorArgs=null
    @SuppressWarnings("unchecked")
    @Override
    public <T> T create(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
        // eg1: classToCreate=ArrayList.class  type=List.class
        // eg1: classToCreate=User.class       type=User.class
        /** 根据type，映射具体的对象类型classToCreate */
        Class<?> classToCreate = resolveInterface(type);

        // eg1: 返回空集合的ArrayList对象  classToCreate=ArrayList.class constructorArgTypes=null constructorArgs=null
        // eg1: 返回User{id=null, name='null', age=null, userContacts=null}  classToCreate=User.class constructorArgTypes=null constructorArgs=null
        /** 利用反射，生成对象 */
        return (T) instantiateClass(classToCreate, constructorArgTypes, constructorArgs);
    }

    @Override
    public void setProperties(Properties properties) {
        // no props for default
    }

    /**
     * 初始化对象
     *
     * @param type
     * @param constructorArgTypes
     * @param constructorArgs
     * @param <T>
     *
     * @return
     */
    // eg1: type=ArrayList.class constructorArgTypes=null constructorArgs=null
    <T> T instantiateClass(Class<T> type, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
        try {
            Constructor<T> constructor;

            // eg1: constructorArgTypes=null constructorArgs=null
            /** 如果是调用无参数的默认构造方法 */
            if (constructorArgTypes == null || constructorArgs == null) {
                // eg1: type=ArrayList.class
                constructor = type.getDeclaredConstructor(); /** 取得默认构造方法对象 */
                if (!constructor.isAccessible()) {
                    /** 为反射对象设置可访问标志，flag为true表明屏蔽Java语言的访问检查，使得对象的私有属性也可以被查询和设置 */
                    constructor.setAccessible(true);
                }
                return constructor.newInstance(); /** 生成实例 */
            }

            /** 调用有参数的构造方法 */
            constructor = type.getDeclaredConstructor(constructorArgTypes.toArray(new Class[constructorArgTypes.size()]));
            if (!constructor.isAccessible()) {
                constructor.setAccessible(true);
            }
            return constructor.newInstance(constructorArgs.toArray(new Object[constructorArgs.size()])); // 生成实例
        } catch (Exception e) {
            StringBuilder argTypes = new StringBuilder();
            if (constructorArgTypes != null && !constructorArgTypes.isEmpty()) {
                for (Class<?> argType : constructorArgTypes) {
                    argTypes.append(argType.getSimpleName());
                    argTypes.append(",");
                }
                argTypes.deleteCharAt(argTypes.length() - 1); // 移除最后一个逗号
            }
            StringBuilder argValues = new StringBuilder();
            if (constructorArgs != null && !constructorArgs.isEmpty()) {
                for (Object argValue : constructorArgs) {
                    argValues.append(String.valueOf(argValue));
                    argValues.append(",");
                }
                argValues.deleteCharAt(argValues.length() - 1); // 移除最后一个逗号
            }
            throw new ReflectionException(
                    "Error instantiating " + type + " with invalid types (" + argTypes + ") or values (" + argValues
                            + "). Cause: " + e, e);
        }
    }

    /**
     * 对类型进行映射转换
     *
     * @param type 原始类的类型
     *
     * @return 映射后的类型
     */
    // eg1: type=List.class
    // eg1: type=User.class
    protected Class<?> resolveInterface(Class<?> type) {
        Class<?> classToCreate;
        if (type == List.class || type == Collection.class || type == Iterable.class) {
            // eg1: type = List.class
            classToCreate = ArrayList.class;
        } else if (type == Map.class) {
            classToCreate = HashMap.class;
        } else if (type == SortedSet.class) { // issue #510 Collections Support
            classToCreate = TreeSet.class;
        } else if (type == Set.class) {
            classToCreate = HashSet.class;
        } else {
            // eg1: type=User.class
            classToCreate = type;
        }
        // eg1: 返回ArrayList.class
        // eg1: 返回User.class
        return classToCreate;
    }

    /**
     * instanceof运算符 只被用于对象引用变量，检查左边的被测试对象 是不是 右边类或接口的实例化。如果被测对象是null值，则测试结果总是false。
     * 形象地：自身实例或子类实例 instanceof 自身类  返回true
     * 例： String s=new String("javaisland");
     * System.out.println(s instanceof String); //true
     * <p>
     * Class类的isInstance(Object obj)方法，obj是被测试的对象，如果obj是调用这个方法的class或接口的实例，则返回true。这个方法是instanceof运算符的动态等价。
     * 形象地：自身类.class.isInstance(自身实例或子类实例)  返回true
     * 例：String s=new String("javaisland");
     * System.out.println(String.class.isInstance(s)); //true
     * <p>
     * Class类的isAssignableFrom(Class cls)方法，如果调用这个方法的class或接口 与 参数cls表示的类或接口相同，或者是参数cls表示的类或接口的父类，则返回true。
     * 形象地：自身类.class.isAssignableFrom(自身类或子类.class)  返回true
     * 例：System.out.println(ArrayList.class.isAssignableFrom(Object.class));  //false
     * System.out.println(Object.class.isAssignableFrom(ArrayList.class));  //true
     *
     * @param type Object type
     * @param <T>
     *
     * @return
     */
    @Override
    public <T> boolean isCollection(Class<T> type) {
        return Collection.class.isAssignableFrom(type); // Class直接的对比
    }

}
