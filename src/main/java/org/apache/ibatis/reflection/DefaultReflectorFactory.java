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
package org.apache.ibatis.reflection;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认的反射工厂类实现
 *
 * 功能：
 *     只是使用ConcurrentHashMap来维护［key]: CLass  -------> [value]: Reflector，并提供findForClass方法来返回Reflector实例对象
 */
public class DefaultReflectorFactory implements ReflectorFactory {

    // 开启缓存
    private boolean classCacheEnabled = true;

    // 使用ConcurrentHashMap维护每一个class文件对应一个Reflector
    private final ConcurrentMap<Class<?>, Reflector> reflectorMap = new ConcurrentHashMap<Class<?>, Reflector>();

    public DefaultReflectorFactory() {
    }

    @Override
    public boolean isClassCacheEnabled() {
        return classCacheEnabled;
    }

    @Override
    public void setClassCacheEnabled(boolean classCacheEnabled) {
        this.classCacheEnabled = classCacheEnabled;
    }

    // eg：type=Configuration.class
    @Override
    public Reflector findForClass(Class<?> type) {
        // eg：如果没设置classCacheEnabled，则第一次进入时，classCacheEnabled=true
        if (classCacheEnabled) {
            Reflector cached = reflectorMap.get(type);
            // eg：第一次，reflectorMap为空，所以cached一定为null
            if (cached == null) {
                cached = new Reflector(type); // eg: 初始化Configuration的反射器Reflector
                reflectorMap.put(type, cached); // eg: 维护到reflectorMap中，key=Configuration.class  value=new Reflector(type)
            }
            return cached;
        } else {
            return new Reflector(type);
        }
    }

}
