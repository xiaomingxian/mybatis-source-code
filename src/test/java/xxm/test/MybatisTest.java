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
    /**
     * 第一阶段：获取MapperProxy
     * 第二阶段：获取mapperMethod对象 mapperMethod.execute()
     *
     */


    public static void main(String[] args) throws Exception {
        String resource = "mybatis-config.xml";
        InputStream inputStream = Resources.getResourceAsStream(resource);
        /**1 创建SqlSessionFacory -> 构建出一个Configuration(其中还包括MappedStatements XXXMapper的配置类)
         *Configuration configuration;
         *configuration.getMappedStatements()
         *SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
         */
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
        /******************************分割线******************************/
        SqlSession sqlSession = sqlSessionFactory.openSession();
        //2 获取Mapper
        //2-1 MapperRegister 类扫描包注册mapper( knownMappers.put(type, new MapperProxyFactory<T>(type));) //type就是DemoMapper.class
        // 位置:org.apache.ibatis.binding.MapperRegistry.addMappers(java.lang.String, java.lang.Class<?>)
        //2-2 点进去查看代理类生成过程：1 从knownMappers 获取工厂，通过工厂生成代理对象 jdk动态代理 invoke对方法赠强
        //2-3 看new MapperProxy<T> 的 invoke 方法 解析方法 调用execute
        /** 初始化一个MapperMethod并放入缓存中 或者 从缓存中取出之前的MapperMethod（中有2个静态内部类）  methodCache.put(method, mapperMethod);
         * 包含 SqlCommand (id(mapperInterface.getName() + "." + methodName),type(增删改查)) -->记录了SQL语句的名称和类型
         * MethodSignature --->Mapper接口中对应方法的相关信息
         *
         *  调用MapperMethod.execute()方法执行SQL语句  sqlSession.selectOne() /insert()/....
         *  */
        DemoMapper mapper = sqlSession.getMapper(DemoMapper.class);
        Map<String,Object> map = new HashMap<>();
        map.put("id","123");
        System.out.println(mapper.selectAll(map));
        sqlSession.close();
        sqlSession.commit();
    }


}
