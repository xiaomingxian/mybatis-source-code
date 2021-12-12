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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.session.Configuration;

/**
 * 用于建立SQL和参数
 *
 * An actual SQL String got from an {@link SqlSource} after having processed any dynamic content.
 * The SQL may have SQL placeholders "?" and an list (ordered) of an parameter mappings 
 * with the additional information for each parameter (at least the property name of the input object to read 
 * the value from). 
 * </br>
 * Can also have additional parameters that are created by the dynamic language (for loops, bind...).
 *
 * @author Clinton Begin
 */
public class BoundSql {

    // 我们书写在映射器里面的一条SQL
    private String sql;

    // ParameterMapping对象会描述我们的参数，参数包含属性、名称、表达式、javaType、jdbcType、typeHandler等重要信息
    private List<ParameterMapping> parameterMappings;

    // SQL的传参对象（简单对象、POJO、Map或@Param注释的参数）
    private Object parameterObject;

    private Map<String, Object> additionalParameters;

    private MetaObject metaParameters;

    public BoundSql(Configuration configuration, String sql, List<ParameterMapping> parameterMappings,
                    Object parameterObject) {
        this.sql = sql; // eg1: "select id, name, age from tb_user where id = ?"
        this.parameterMappings = parameterMappings; // eg1: parameterMappings[0] = {property='id', mode=IN, javaType=class java.lang.Long, jdbcType=null, numericScale=null, resultMapId='null', jdbcTypeName='null', expression='null'}
        this.parameterObject = parameterObject; // eg1: {"id": 2L, "param1", 2L}
        this.additionalParameters = new HashMap<>();
        this.metaParameters = configuration.newMetaObject(additionalParameters);
    }

    public String getSql() {
        return sql;
    }

    public List<ParameterMapping> getParameterMappings() {
        return parameterMappings;
    }

    public Object getParameterObject() {
        return parameterObject;
    }

    // eg1: name = "id"
    public boolean hasAdditionalParameter(String name) {
        // eg1: paramName = "id"
        String paramName = new PropertyTokenizer(name).getName();
        // eg1: additionalParameters是空集合，所以返回false
        return additionalParameters.containsKey(paramName);
    }

    public void setAdditionalParameter(String name, Object value) {
        metaParameters.setValue(name, value);
    }

    public Object getAdditionalParameter(String name) {
        return metaParameters.getValue(name);
    }
}
