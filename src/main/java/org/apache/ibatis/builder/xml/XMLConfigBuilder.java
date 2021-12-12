/**
 * Copyright 2009-2020 the original author or authors.
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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * 用于解析XML配置文件，读出配置参数，保存到Configuration中
 * http://www.mybatis.org/mybatis-3/zh/configuration.html#
 *
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

    // XML配置文件是否被解析过，用于控制配置文件仅被解析一次
    private boolean parsed;

    // mybatis的xml配置文件解析器
    private XPathParser parser;

    // 运行环境
    private String environment;

    // 反射工厂
    private ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

    /**
     * ---------提供接收Reader类型的配置信息构造方法---------
     */
    public XMLConfigBuilder(Reader reader) {
        this(reader, null, null);
    }

    public XMLConfigBuilder(Reader reader, String environment) {
        this(reader, environment, null);
    }

    public XMLConfigBuilder(Reader reader, String environment, Properties props) {
        this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    /**
     * ---------提供接收InputStream类型的配置信息构造方法---------
     */
    public XMLConfigBuilder(InputStream inputStream) {
        this(inputStream, null, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment) {
        this(inputStream, environment, null);
    }

    // 走该方法 environment=null properties=null
    public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
        this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
        super(new Configuration());
        ErrorContext.instance().resource("SQL Mapper Configuration");
        this.configuration.setVariables(props);
        this.parsed = false;
        this.environment = environment;
        this.parser = parser;
    }

    /**
     * 解析配置文件
     *
     * @return
     */
    public Configuration parse() {
        // 每个XML配置构造器实例只允许解析配置文件一次
        if (parsed) {
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        parsed = true;
        // 解析config.xml配置文件最重要的方法。选取根节点<configuration>
        parseConfiguration(parser.evalNode("/configuration"));
        return configuration;
    }

    /**
     * 对xml的配置文件解析到Configuration对象中。
     *
     * @param root
     */
    private void parseConfiguration(XNode root) {
        try {
            // 将<properties>的配置转化赋值Configuration的variables属性
            propertiesElement(root.evalNode("properties"));

            // 将<settings>的配置转化赋值Properties，并检查Configuration中是否有对应的属性,如果没有，抛异常，终止整个解析流程。
            Properties settings = settingsAsProperties(root.evalNode("settings"));

            /**
             * 对settings中的vfsImp属性进行二次处理，生成实例对象，并赋值Configuration的vfsImpl属性
             *
             * 为什么解析<settings>中的值为Properties不能都在settingsAsProperties方法中完成，而要单独在loadCustomVfs中完成？
             * 回答；由于vfsImpl配置的是Class，需要通过ClassLoader加载为对象，赋值到Configuration里，而不像其他String类型的value
             * ，可以直接赋值进去并使用。所以单独在loadCustomVfs方法中进行了Configuration中的vfsImpl赋值操作。
             */
            loadCustomVfs(settings);

            // 将<typeAliases>的配置转化赋值Configuration的typeAliasRegistry属性
            typeAliasesElement(root.evalNode("typeAliases"));

            // 解析<plugins>标签，初始化Configuration的interceptorChain
            pluginElement(root.evalNode("plugins"));

            // 解析<objectFactory>标签
            objectFactoryElement(root.evalNode("objectFactory"));

            // 解析<objectWrapperFactory>标签
            objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));

            // 解析<reflectorFactory>标签
            reflectorFactoryElement(root.evalNode("reflectorFactory"));

            // 将配置信息中settings赋值给Configuration
            settingsElement(settings);

            // 解析<environments>标签
            environmentsElement(root.evalNode("environments"));

            // 解析<databaseIdProvider>标签
            databaseIdProviderElement(root.evalNode("databaseIdProvider"));

            // 解析<databaseIdProvider>标签
            typeHandlerElement(root.evalNode("typeHandlers"));

            // 解析<mappers>标签
            mapperElement(root.evalNode("mappers"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
        }
    }

    /**
     * XNode context: 只要是Configuration中存在的属性，都可以通过setting将赋值配置进去
     *
     * <settings>
     * <setting name="cacheEnabled" value="true"/>
     * </settings>
     *
     * @param context
     * @return
     */
    private Properties settingsAsProperties(XNode context) {
        if (context == null) {
            return new Properties();
        }
        // 场景驱动： <setting name="cacheEnabled" value="true"/> ----> Properties props = (key:cacheEnabled value:true)
        Properties props = context.getChildrenAsProperties();

        // 初始化Configuration.class的反射器工厂和反射器
        MetaClass metaConfig =
                MetaClass.forClass(Configuration.class, localReflectorFactory); // DefaultReflectorFactory

        // 场景驱动：key=cacheEnabled  判断<settings>中配置的<setting name="xxx">的xxx。是否在Configuration中有set方法。
        for (Object key : props.keySet()) {
            // 场景驱动： 如果Configuration没有setCacheEnabled方法。则抛出异常
            if (!metaConfig.hasSetter(String.valueOf(key))) {
                throw new BuilderException(
                        "The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
            }
        }
        return props;
    }

    // <setting name="vfsImpl" value="A.class, B.class, C.class"/>
    private void loadCustomVfs(Properties props) throws ClassNotFoundException {
        String value = props.getProperty("vfsImpl");
        if (value != null) {
            String[] clazzes = value.split(",");
            for (String clazz : clazzes) {
                if (!clazz.isEmpty()) {
                    // 通过ClassLoader加载value中配置的VFS实现类
                    @SuppressWarnings("unchecked")
                    Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources.classForName(clazz);
                    // 设置到Configuraiton中
                    configuration.setVfsImpl(vfsImpl);
                }
            }
        }
    }

    /**
     * 类型别名解析
     *
     * @param parent
     */
    private void typeAliasesElement(XNode parent) {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                /**
                 * <typeAliases>
                 *   <typeAlias type="domain.blog.User" />
                 *   <typeAlias alias="Author" type="domain.blog.Author"/>
                 * </typeAliases>
                 *
                 * 可以指定一个包名，MyBatis会在包名下面搜索需要的Java Bean
                 * <typeAliases>
                 *      <package name="org.apache.ibatis.muse.mybatis"/>
                 * </typeAliases>
                 * 扫描每一个在包domain.blog中的Java Bean，在没有注解的情况下，会使用Bean的首字母小写的非限定类名来作为它的别名。
                 * 比如 domain.blog.Author 的别名为 author；若有注解，则别名为其注解值。
                 * @Alias("author")
                 * public class Author {
                 *      ...
                 * }
                 */
                // eg：  <package name="org.apache.ibatis.muse.mybatis"/>
                if ("package".equals(child.getName())) {
                    String typeAliasPackage = child.getStringAttribute("name");
                    // eg: typeAliasPackage="org.apache.ibatis.muse.mybatis"
                    configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
                } else {
                    String alias = child.getStringAttribute("alias");
                    String type = child.getStringAttribute("type");
                    try {
                        Class<?> clazz = Resources.classForName(type);
                        if (alias == null) {
                            // eg: type="domain.blog.User"
                            typeAliasRegistry.registerAlias(clazz);
                        } else {
                            // eg: alias="Author" type="domain.blog.Author"
                            typeAliasRegistry.registerAlias(alias, clazz);
                        }
                    } catch (ClassNotFoundException e) {
                        throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                    }
                }
            }
        }
    }

    /**
     * <plugins>
     * <plugin interceptor="org.mybatis.example.AExampleInterceptor">
     * <property name="asomeProperty" value="100"/>
     * </plugin>
     * <plugin interceptor="org.mybatis.example.BExampleInterceptor">
     * <property name="bsomeProperty" value="200"/>
     * </plugin>
     * </plugins>
     */
    private void pluginElement(XNode parent) throws Exception {
        if (parent != null) {
            // eg:
            //child:
            //<plugin interceptor="org.mybatis.example.AExampleInterceptor">
            //   <property name="asomeProperty" value="100"/>
            //</plugin>
            for (XNode child : parent.getChildren()) {
                // eg: interceptor="org.mybatis.example.AExampleInterceptor"
                String interceptor = child.getStringAttribute("interceptor");
                // eg: 解析<property name="asomeProperty" value="100"/>，将name和value赋值properties
                Properties properties = child.getChildrenAsProperties();
                // 将"org.mybatis.example.AExampleInterceptor"生成Class，并调用newInstance生成对象实例
                Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
                // 将设置的参数传递给AExampleInterceptor
                interceptorInstance.setProperties(properties);
                // 将AExampleInterceptor维护到Configuration的interceptorChain容器中。
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    /**
     * <objectFactory type="org.mybatis.example.ExampleObjectFactory">
     * <property name="someProperty" value="100"/>
     * </objectFactory>
     */
    private void objectFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // eg: type="org.mybatis.example.ExampleObjectFactory"
            String type = context.getStringAttribute("type");
            // eg: <property name="someProperty" value="100"/>
            Properties properties = context.getChildrenAsProperties();
            // eg: 实例化ExampleObjectFactory，必须实现ObjectFactory接口
            ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
            // eg: 将properties赋值给ExampleObjectFactory
            factory.setProperties(properties);
            // eg: 将objectFactory对象赋值Configuration的objectFactory属性
            configuration.setObjectFactory(factory);
        }
    }

    /**
     * <objectWrapperFactory type="org.mybatis.example.ExampleWrapperFactory"/>
     */
    private void objectWrapperFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // eg: type="org.mybatis.example.ExampleWrapperFactory"
            String type = context.getStringAttribute("type");
            // eg: 实例化ExampleWrapperFactory，必须实现ObjectWrapperFactory接口
            ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
            // eg: 将ExampleWrapperFactory对象赋值Configuration的objectWrapperFactory属性
            configuration.setObjectWrapperFactory(factory);
        }
    }

    /**
     * <reflectorFactory type="org.mybatis.example.ExampleReflectorFactory"/>
     */
    private void reflectorFactoryElement(XNode context) throws Exception {
        if (context != null) {
            // eg: type="org.mybatis.example.ExampleReflectorFactory"
            String type = context.getStringAttribute("type");
            ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
            configuration.setReflectorFactory(factory);
        }
    }

    /**
     * 解析<properties>配置信息
     *
     * @param context
     * @throws Exception
     */
    private void propertiesElement(XNode context) throws Exception {
        if (context != null) {
            Properties defaults = context.getChildrenAsProperties();
            /**
             * 这两种方式都可以用来引入配置文件。但是它们又有所不同:
             * <properties resource="org/mybatis/example/config.properties"/>  --- 用来引入类路径下的资源
             * <properties url="xxxxx"/> --- 用来引入网络路径或者磁盘路径下的资源
             */
            String resource = context.getStringAttribute("resource");
            String url = context.getStringAttribute("url");

            // <properties resource=""/>与<properties url=""/>不能同时被配置
            if (resource != null && url != null) {
                throw new BuilderException(
                        "The properties element cannot specify both a URL and a resource based property file "
                                + "reference.  Please specify one or the other.");
            }
            if (resource != null) {
                // 通过ClassLoader加载配置文件转换为字节输入流
                defaults.putAll(Resources.getResourceAsProperties(resource));
            } else if (url != null) {
                defaults.putAll(Resources.getUrlAsProperties(url));
            }
            Properties vars = configuration.getVariables();
            if (vars != null) {
                defaults.putAll(vars);
            }
            parser.setVariables(defaults);
            configuration.setVariables(defaults);
        }
    }

    /**
     * 解析<settings>配置参数
     *
     * <settings>
     * <setting name="cacheEnabled" value="true"/>
     * <setting name="lazyLoadingEnabled" value="true"/>
     * <setting name="multipleResultSetsEnabled" value="true"/>
     * <setting name="useColumnLabel" value="true"/>
     * <setting name="useGeneratedKeys" value="false"/>
     * <setting name="autoMappingBehavior" value="PARTIAL"/>
     * <setting name="autoMappingUnknownColumnBehavior" value="WARNING"/>
     * <setting name="defaultExecutorType" value="SIMPLE"/>
     * <setting name="defaultStatementTimeout" value="25"/>
     * <setting name="defaultFetchSize" value="100"/>
     * <setting name="safeRowBoundsEnabled" value="false"/>
     * <setting name="mapUnderscoreToCamelCase" value="false"/>
     * <setting name="localCacheScope" value="SESSION"/>
     * <setting name="jdbcTypeForNull" value="OTHER"/>
     * <setting name="lazyLoadTriggerMethods" value="equals,clone,hashCode,toString"/>
     * </settings>
     *
     * @param props
     * @throws Exception
     */
    private void settingsElement(Properties props) throws Exception {
        /**
         * 指定MyBatis应如何自动映射列到字段或属性。
         *
         * NONE：表示取消自动映射；
         * PARTIAL：只会自动映射没有定义嵌套结果集映射的结果集（默认值）。
         * FULL：会自动映射任意复杂的结果集（无论是否嵌套）。
         */
        configuration.setAutoMappingBehavior(
                AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));

        /**
         * 指定发现自动映射目标未知列（或者未知属性类型）的行为。
         *
         * NONE：不做任何反应（默认值）
         * WARNING：输出提醒日志 ('org.apache.ibatis.session.AutoMappingUnknownColumnBehavior' 的日志等级必须设置为 WARN)
         * FAILING：映射失败 (抛出 SqlSessionException)
         */
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior
                .valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));

        /**
         * 该配置影响的所有映射器中配置的缓存的全局开关。
         *
         * true | false
         */
        configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));

        /**
         * 指定 Mybatis 创建具有延迟加载能力的对象所用到的代理工具。
         * CGLIB | JAVASSIST (MyBatis 3.3 or above是默认值)
         *
         * 场景驱动： proxyFactory="JAVASSIST"
         */
        configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));

        /**
         * 延迟加载的全局开关。当开启时，所有关联对象都会延迟加载。 特定关联关系中可通过设置fetchType属性来覆盖该项的开关状态。
         */
        configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));

        /**
         * 当开启时，任何方法的调用都会加载该对象的所有属性。否则，每个属性会按需加载（参考lazyLoadTriggerMethods).
         */
        configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));

        /**
         * 是否允许单一语句返回多结果集（需要兼容驱动）。
         */
        configuration
                .setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));

        /**
         * 使用列标签代替列名。不同的驱动在这方面会有不同的表现， 具体可参考相关驱动文档或通过测试这两种不同的模式来观察所用驱动的结果。
         */
        configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));

        /**
         * 允许 JDBC 支持自动生成主键，需要驱动兼容。 如果设置为 true 则这个设置强制使用自动生成主键，尽管一些驱动不能兼容但仍可正常工作（比如 Derby）。
         */
        configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));

        /**
         * 配置默认的执行器。
         * SIMPLE——就是普通的执行器（默认值）；
         * REUSE——执行器会重用预处理语句（prepared statements）；
         * BATCH——执行器将重用语句并执行批量更新。
         */
        configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));

        /**
         * 设置超时时间，它决定驱动等待数据库响应的秒数。任意正整数
         */
        configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));

        /**
         * 为驱动的结果集获取数量（fetchSize）设置一个提示值。此参数只可以在查询设置中被覆盖。
         */
        configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));

        /**
         * 是否开启自动驼峰命名规则（camel case）映射，即从经典数据库列名 A_COLUMN 到经典 Java 属性名 aColumn 的类似映射。默认false
         */
        configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));

        /**
         * 允许在嵌套语句中使用分页（RowBounds）。如果允许使用则设置为false。默认false
         */
        configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));

        /**
         * MyBatis 利用本地缓存机制（Local Cache）防止循环引用（circular references）和加速重复嵌套查询。
         * SESSION：这种情况下会缓存一个会话中执行的所有查询。 （默认值）
         * STATEMENT：本地会话仅用在语句执行上，对相同 SqlSession 的不同调用将不会共享数据。
         */
        configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));

        /**
         * 当没有为参数提供特定的 JDBC 类型时，为空值指定 JDBC 类型。 某些驱动需要指定列的 JDBC 类型，多数情况直接用一般类型即可，
         * 比如 NULL、VARCHAR 或 OTHER。默认值为OTHER
         */
        configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));

        /**
         * 指定哪个对象的方法触发一次延迟加载。
         */
        configuration.setLazyLoadTriggerMethods(
                stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));

        /**
         * 允许在嵌套语句中使用分页（ResultHandler）。如果允许使用则设置为false。	默认值为true
         */
        configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));

        /**
         * 指定动态 SQL 生成的默认语言。
         * 默认为org.apache.ibatis.scripting.xmltags.XMLLanguageDriver
         */
        configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));

        /**
         * 指定当结果集中值为 null 的时候是否调用映射对象的 setter（map 对象时为 put）方法，这对于有 Map.keySet() 依赖或 null 值初始化
         * 的时候是有用的。注意基本类型（int、boolean等）是不能设置成 null 的。
         */
        configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));

        /**
         * 允许使用方法签名中的名称作为语句参数名称。 为了使用该特性，你的工程必须采用Java 8编译，并且加上-parameters选项。（从3.4.1开始）
         * 默认为true
         */
        configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));

        /**
         * 当返回行的所有列都是空时，MyBatis默认返回null。 当开启这个设置时，MyBatis会返回一个空实例。
         * 请注意，它也适用于嵌套的结果集 (i.e. collectioin and association)。（从3.4.2开始）
         */
        configuration
                .setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));

        /**
         * 指定 MyBatis 增加到日志名称的前缀。
         */
        configuration.setLogPrefix(props.getProperty("logPrefix"));

        /**
         * 指定 MyBatis 所用日志的具体实现，未指定时将自动查找。
         * SLF4J | LOG4J | LOG4J2 | JDK_LOGGING | COMMONS_LOGGING | STDOUT_LOGGING | NO_LOGGING
         */
        @SuppressWarnings("unchecked")
        Class<? extends Log> logImpl = (Class<? extends Log>) resolveClass(props.getProperty("logImpl"));
        configuration.setLogImpl(logImpl);

        /**
         * 指定一个提供Configuration实例的类. 这个被返回的Configuration实例是用来加载被反序列化对象的懒加载属性值.
         * 这个类必须包含一个签名方法static Configuration getConfiguration(). (从 3.2.3 版本开始)
         */
        configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    }

    /**
     * <environments default="dev">
     * <environment id="dev">
     * <transactionManager type="JDBC">
     * <property name="..." value="..."/>
     * </transactionManager>
     * <dataSource type="POOLED">
     * <property name="driver" value="${driver}"/>
     * <property name="url" value="${url}"/>
     * <property name="username" value="${username}"/>
     * <property name="password" value="${password}"/>
     * </dataSource>
     * </environment>
     * </environments>
     */
    private void environmentsElement(XNode context) throws Exception {
        if (context != null) {
            if (environment == null) {
                // eg: environment="dev"
                environment = context.getStringAttribute("default");
            }
            /**
             * <environment id="dev">
             *   <transactionManager type="JDBC">
             *     <property name="..." value="..."/>
             *   </transactionManager>
             *   <dataSource type="POOLED">
             *     <property name="driver" value="${driver}"/>
             *     <property name="url" value="${url}"/>
             *     <property name="username" value="${username}"/>
             *     <property name="password" value="${password}"/>
             *   </dataSource>
             * </environment>
             */
            for (XNode child : context.getChildren()) {
                // id="dev"
                String id = child.getStringAttribute("id");
                // 如果id与environment相同，则解析配置文件
                if (isSpecifiedEnvironment(id)) {
                    TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
                    DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
                    DataSource dataSource = dsFactory.getDataSource();
                    // 构造者模式
                    Environment.Builder environmentBuilder = new Environment.Builder(id)
                            .transactionFactory(txFactory)
                            .dataSource(dataSource);
                    configuration.setEnvironment(environmentBuilder.build());
                }
            }
        }
    }

    /**
     * <databaseIdProvider type="DB_VENDOR">
     * <property name="SQL Server" value="sqlserver"/>
     * <property name="DB2" value="db2"/>
     * <property name="Oracle" value="oracle" />
     * </databaseIdProvider>
     */
    private void databaseIdProviderElement(XNode context) throws Exception {
        DatabaseIdProvider databaseIdProvider = null;
        if (context != null) {
            // type="DB_VENDOR"
            String type = context.getStringAttribute("type");
            if ("VENDOR".equals(type)) {
                type = "DB_VENDOR";
            }
            // 解析property子属性，生成Properties
            Properties properties = context.getChildrenAsProperties();

            // 生成VendorDatabaseIdProvider对象
            databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
            databaseIdProvider.setProperties(properties);
        }
        Environment environment = configuration.getEnvironment();
        if (environment != null && databaseIdProvider != null) {
            String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
            configuration.setDatabaseId(databaseId);
        }
    }

    /**
     * <transactionManager type="JDBC">
     * <property name="..." value="..."/>
     * </transactionManager>
     */
    private TransactionFactory transactionManagerElement(XNode context) throws Exception {
        if (context != null) {
            // type="JDBC"
            String type = context.getStringAttribute("type");
            // <property name="..." value="..."/>生成Properties
            Properties props = context.getChildrenAsProperties();
            // 初始化JDBC JdbcTransactionFactory
            TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
            // JdbcTransactionFactory的setProperties是空方法，并未进行任何赋值
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a TransactionFactory.");
    }

    /**
     * <dataSource type="POOLED">
     * <property name="driver" value="${driver}"/>
     * <property name="url" value="${url}"/>
     * <property name="username" value="${username}"/>
     * <property name="password" value="${password}"/>
     * </dataSource>
     */
    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        if (context != null) {
            // type="POOLED"
            String type = context.getStringAttribute("type");
            /**
             * <property name="driver" value="${driver}"/>
             * <property name="url" value="${url}"/>
             * <property name="username" value="${username}"/>
             * <property name="password" value="${password}"/>
             */
            Properties props = context.getChildrenAsProperties();
            // PooledDataSourceFactory
            DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
            // 调用父类——UnpooledDataSourceFactory的setProperties方法
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    /**
     * <typeHandlers>
     * <typeHandler jdbcType="VARCHAR" javaType="date" handler="com.daily.handler.MyDateHandler" />
     * </typeHandlers>
     * 或者
     * <typeHandlers>
     * <package name="org.mybatis.example"/>
     * </typeHandlers>
     */
    private void typeHandlerElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                if ("package".equals(child.getName())) {
                    // typeHandlerPackage="org.mybatis.example"
                    String typeHandlerPackage = child.getStringAttribute("name");

                    typeHandlerRegistry.register(typeHandlerPackage);
                } else {
                    // eg: javaTypeName="date"
                    String javaTypeName = child.getStringAttribute("javaType");

                    // eg: jdbcTypeName="VARCHAR"
                    String jdbcTypeName = child.getStringAttribute("jdbcType");

                    // eg: handlerTypeName="com.daily.handler.MyDateHandler"
                    String handlerTypeName = child.getStringAttribute("handler");

                    // eg: javaTypeClass=Date.class
                    Class<?> javaTypeClass = resolveClass(javaTypeName);

                    // eg: Types.VARCHAR (JDK的rt.jar包里的类java.sql.Type)
                    JdbcType jdbcType = resolveJdbcType(jdbcTypeName);

                    // eg：MyDateHandler.class
                    Class<?> typeHandlerClass = resolveClass(handlerTypeName);

                    // 注册typeHandler
                    if (javaTypeClass != null) {
                        if (jdbcType == null) {
                            typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
                        } else {
                            // javaTypeClass=Date.class  jdbcType=Types.VARCHAR  typeHandlerClass=com.daily.handler
                            // .MyDateHandler
                            typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
                        }
                    } else {
                        typeHandlerRegistry.register(typeHandlerClass);
                    }
                }
            }
        }
    }

    /**
     * (1) 使用相对于类路径的资源引用
     * <mappers>
     * <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
     * <mapper resource="org/mybatis/builder/BlogMapper.xml"/>
     * <mapper resource="org/mybatis/builder/PostMapper.xml"/>
     * </mappers>
     * <p>
     * (2) 使用完全限定资源定位符（URL）
     * <mappers>
     * <mapper url="file:///var/mappers/AuthorMapper.xml"/>
     * <mapper url="file:///var/mappers/BlogMapper.xml"/>
     * <mapper url="file:///var/mappers/PostMapper.xml"/>
     * </mappers>
     * <p>
     * (3) 使用映射器接口实现类的完全限定类名
     * <mappers>
     * <mapper class="org.mybatis.builder.AuthorMapper"/>
     * <mapper class="org.mybatis.builder.BlogMapper"/>
     * <mapper class="org.mybatis.builder.PostMapper"/>
     * </mappers>
     * <p>
     * (4) 将包内的映射器接口实现全部注册为映射器
     * <mappers>
     * <package name="org.mybatis.builder"/>
     * </mappers>
     */
    private void mapperElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                if ("package".equals(child.getName())) {
                    // eg: mapperPackage="org.mybatis.builder"
                    String mapperPackage = child.getStringAttribute("name");
                    configuration.addMappers(mapperPackage);
                } else {
                    String resource = child.getStringAttribute("resource");
                    String url = child.getStringAttribute("url");
                    String mapperClass = child.getStringAttribute("class");
                    if (resource != null && url == null && mapperClass == null) {
                        ErrorContext.instance().resource(resource);
                        InputStream inputStream = Resources.getResourceAsStream(resource);
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource,
                                configuration.getSqlFragments());
                        mapperParser.parse();
                    } else if (resource == null && url != null && mapperClass == null) {
                        ErrorContext.instance().resource(url);
                        InputStream inputStream = Resources.getUrlAsStream(url);
                        XMLMapperBuilder mapperParser =
                                new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
                        mapperParser.parse();
                    } else if (resource == null && url == null && mapperClass != null) {
                        Class<?> mapperInterface = Resources.classForName(mapperClass);
                        configuration.addMapper(mapperInterface);
                    } else {
                        throw new BuilderException(
                                "A mapper element may only specify a url, resource or class, but not more than one.");
                    }
                }
            }
        }
    }

    /**
     * 判断是id值是被指定的环境
     *
     * @param id
     * @return
     */
    private boolean isSpecifiedEnvironment(String id) {
        if (environment == null) {
            throw new BuilderException("No environment specified.");
        } else if (id == null) {
            throw new BuilderException("Environment requires an id attribute.");
        } else if (environment.equals(id)) {
            return true;
        }
        return false;
    }

}
