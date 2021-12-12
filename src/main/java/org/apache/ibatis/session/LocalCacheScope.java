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
package org.apache.ibatis.session;

/**
 *  一级缓存的范围有SESSION和STATEMENT两种，默认是SESSION。
 *  如果我们不需要使用一级缓存，那么我们可以把一级缓存的范围指定为STATEMENT，这样每次执行完一个Mapper语句后都会将一级缓存清除。
 *  如果需要更改一级缓存的范围，请在Mybatis的配置文件中，在<settings>下通过localCacheScope指定。
 *       <setting name="localCacheScope" value="SESSION"/>
 *
 * @author Eduardo Macarron
 */
public enum LocalCacheScope {
    SESSION, STATEMENT
}
