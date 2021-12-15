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
        /**
         * 1.第一阶段 生成Mapper
         */
        String resource = "mybatis-config.xml";
        InputStream inputStream = Resources.getResourceAsStream(resource);
        //1 创建SqlSessionFacory -> 构建出一个Configuration(其中还包括MappedStatements XXXMapper的配置类)
        //Configuration configuration;
        //configuration.getMappedStatements()
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
        /******************************分割线******************************/
        SqlSession sqlSession = sqlSessionFactory.openSession();
        //2 获取Mapper
        //2-1 MapperRegister 类扫描包注册mapper( knownMappers.put(type, new MapperProxyFactory<T>(type));) //type就是DemoMapper.class
        // 位置:org.apache.ibatis.binding.MapperRegistry.addMappers(java.lang.String, java.lang.Class<?>)
        //2-2 点进去查看代理类生成过程：1 从knownMappers 获取工厂，通过工厂生成代理对象 jdk动态代理 invoke对方法赠强
        DemoMapper mapper = sqlSession.getMapper(DemoMapper.class);
        Map<String,Object> map = new HashMap<>();
        map.put("id","123");
        System.out.println(mapper.selectAll(map));
        sqlSession.close();
        sqlSession.commit();
    }


}
