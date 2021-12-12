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
package org.apache.ibatis.muse.mybatis;

import java.io.FileInputStream;
import java.io.InputStream;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

public class MybatisTest {

    public static SqlSessionFactory sessionFactory;

    public static SqlSession getSession() {

        try {
            InputStream inputStream = new FileInputStream(
                    "/Users/lijinlong02/alibaba/mybatis-3-mybatis-3.4"
                            + ".4/src/test/java/org/apache/ibatis/muse/mybatis/mybatis-config.xml");
            sessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
        } catch (Exception e) {
            System.out.println(e);
        }
        return sessionFactory.openSession();
    }

    public static void main(String[] args) {
        SqlSession session = getSession();
        UserMapper userMapper = session.getMapper(UserMapper.class);
        try {
            UserDomain userDomain = userMapper.selectById(8);
            System.out.println(userDomain);
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}