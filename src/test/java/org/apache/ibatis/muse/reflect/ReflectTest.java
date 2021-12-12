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
package org.apache.ibatis.muse.reflect;

import java.lang.reflect.Method;

import org.apache.ibatis.muse.reflect.cglib.ReflectServiceCglib;
import org.apache.ibatis.muse.reflect.jdkproxy.HelloService;
import org.apache.ibatis.muse.reflect.jdkproxy.HelloServiceImpl;
import org.apache.ibatis.muse.reflect.jdkproxy.HelloServiceJdkProxy;
import org.junit.Test;

/**
 * 反射和代理
 *
 * Date 2018/1/26 下午7:28
 * Author lijinlong02@baidu.com
 */
public class ReflectTest {

    /** ------------------------------Java 反射------------------------------- **/
    @Test
    public void testReflection() throws Throwable {
        Object obj = Class.forName(ReflectService.class.getName()).newInstance();
        Class clazz = obj.getClass();
        Method method = clazz.getMethod("sayHello", String.class);
        method.invoke(obj, "muse");
    }

    @Test
    public void testReflection1() throws Throwable {
        Object obj = ReflectService.class.newInstance();
        Method method = obj.getClass().getMethod("sayHello", String.class);
        method.invoke(obj, "muse1");
    }

    @Test
    public void testReflection2() throws Throwable {
        Class clazz = ReflectService.class;
        Method method = clazz.getMethod("sayHello", String.class);
        method.invoke(clazz.newInstance(), "muse2");
    }

    /** ------------------------------Jdk动态代理------------------------------- **/
    @Test
    public void testJdkProxy() throws Throwable {
        HelloService helloService = new HelloServiceImpl(); // 需要被代理的对象有接口实现
        HelloService proxy = (HelloService) new HelloServiceJdkProxy().getProxy(helloService);
        proxy.sayHello("jdk");
    }

    /** ------------------------------Cglib动态代理------------------------------- **/
    @Test
    public void testCglibProxy() throws Throwable {
        ReflectService target = new ReflectService();
        ReflectServiceCglib cglib = new ReflectServiceCglib();
        ReflectService proxy = (ReflectService)cglib.getProxy(target);
        proxy.sayHello("cglib");
    }

}
