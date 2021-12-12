/*
 * Copyright (C) 2021 Baidu, Inc. All Rights Reserved.
 */
package xxm.test;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import java.io.InputStream;
import java.util.Map;

public class MybatisTest {
    public static void main(String[] args) throws Exception {
        String resource = "mybatis-config.xml";
        InputStream inputStream = Resources.getResourceAsStream(resource);
        //创建SqlSessionFacory
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
        /******************************分割线******************************/
        SqlSession sqlSession = sqlSessionFactory.openSession();
        //获取Mapper
        DemoMapper mapper = sqlSession.getMapper(DemoMapper.class);
        Map<String,Object> map = new HashMap<>();
        map.put("id","123");
        System.out.println(mapper.selectAll(map));
        sqlSession.close();
        sqlSession.commit();
    }


}
