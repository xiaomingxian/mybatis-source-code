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
package org.apache.ibatis.executor.result;

import java.util.ArrayList;
import java.util.List;

import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;

/**
 * 存储查询结果
 *
 * @author Clinton Begin
 */
public class DefaultResultHandler implements ResultHandler<Object> {

    private final List<Object> list;

    public DefaultResultHandler() {
        list = new ArrayList<>();
    }

    // eg1: objectFactory = DefaultObjectFactory
    @SuppressWarnings("unchecked")
    public DefaultResultHandler(ObjectFactory objectFactory) {
        list = objectFactory.create(List.class); // eg1: 生成空集合的ArrayList对象返回
    }

    // eg1:
    /**
     * 解析结果，并存放到list中
     */
    @Override
    public void handleResult(ResultContext<? extends Object> context) {
        // eg1: context.getResultObject()=User{id=2, name='muse2', age=24, userContacts=null}
        list.add(context.getResultObject());
    }

    public List<Object> getResultList() {
        return list;
    }

}
