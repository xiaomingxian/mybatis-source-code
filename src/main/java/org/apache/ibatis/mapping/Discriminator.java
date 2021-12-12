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
package org.apache.ibatis.mapping;

import java.util.Collections;
import java.util.Map;

import org.apache.ibatis.session.Configuration;

/**
 * 鉴别器 <discriminator/>
 *
 * @author Clinton Begin
 */
public class Discriminator {

    private ResultMapping resultMapping;
    private Map<String, String> discriminatorMap;

    Discriminator() {
    }

    public static class Builder {
        private Discriminator discriminator = new Discriminator();

        public Builder(Configuration configuration, ResultMapping resultMapping, Map<String, String> discriminatorMap) {
            discriminator.resultMapping = resultMapping;
            discriminator.discriminatorMap = discriminatorMap;
        }

        public Discriminator build() {
            /**
             * 在Java中，assert关键字是从JAVA SE 1.4 引入的，为了避免和老版本的Java代码中使用了assert关键字导致错误，Java在执行的时候默
             * 认是不启动断言检查的（这个时候，所有的断言语句都将忽略！），如果要开启断言检查，则需要用开关-enableassertions或-ea来开启。
             *
             * assert关键字语法很简单，有两种用法：
             *
             * 1、assert <boolean表达式>
             * 如果<boolean表达式>为true，则程序继续执行。
             * 如果为false，则程序抛出AssertionError，并终止执行。
             *
             * 2、assert <boolean表达式> : <错误信息表达式>
             * 如果<boolean表达式>为true，则程序继续执行。
             * 如果为false，则程序抛出java.lang.AssertionError，并输入<错误信息表达式>。
             *
             * public class AssertFoo {
             *     public static void main(String args[]) {
             *         //断言1结果为true，则继续往下执行
             *         assert true;
             *         System.out.println("断言1没有问题，Go！");
             *         System.out.println("\n-----------------\n");
             *         //断言2结果为false,程序终止
             *         assert false : "断言失败，此表达式的信息将会在抛出异常的时候输出！";
             *         System.out.println("断言2没有问题，Go！");
             *     }
             * }
             *
             * 保存代码到C:\AssertFoo.java，然后按照下面的方式执行，查看控制台输出结果：
             *
             * 1、编译程序：
             * C:\>javac AssertFoo.java
             *
             * 2、默认执行程序，没有开启-ea开关：
             * C:\>java AssertFoo
             * 断言1没有问题，Go！
             *
             * -----------------
             *
             * 断言2没有问题，Go！
             *
             * 3、开启-ea开关，执行程序：
             * C:\>java -ea AssertFoo
             * 断言1没有问题，Go！
             *
             * -----------------
             *
             * Exception in thread "main" java.lang.AssertionError: 断言失败，此表达式的信息将
             * 会在抛出异常的时候输出！
             *         at AssertFoo.main(AssertFoo.java:10)
             */
            assert discriminator.resultMapping != null;
            assert discriminator.discriminatorMap != null;
            assert !discriminator.discriminatorMap.isEmpty();
            //lock down map
            discriminator.discriminatorMap = Collections.unmodifiableMap(discriminator.discriminatorMap);
            return discriminator;
        }
    }

    public ResultMapping getResultMapping() {
        return resultMapping;
    }

    public Map<String, String> getDiscriminatorMap() {
        return discriminatorMap;
    }

    public String getMapIdFor(String s) {
        return discriminatorMap.get(s);
    }

}
