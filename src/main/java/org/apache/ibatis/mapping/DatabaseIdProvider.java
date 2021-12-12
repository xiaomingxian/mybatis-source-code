/**
 *    Copyright 2009-2017 the original author or authors.
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
package org.apache.ibatis.mapping;

import java.sql.SQLException;
import java.util.Properties;

import javax.sql.DataSource;

/**
 * 数据库厂商标识，适用于mybatis在多种数据库厂商环境下，执行不同的sql使用。
 *
 * Should return an id to identify the type of this database.
 * That id can be used later on to build different queries for each database type
 * This mechanism enables supporting multiple vendors or versions
 * 
 * @author Eduardo Macarron
 * @modify muse
 */
public interface DatabaseIdProvider {

  /**
   * 从Properties中获取mybatis中针对数据库厂商标识的配置信息
   *
   * @param p
   */
  void setProperties(Properties p);

  /**
   * 通过数据源，获得产品名称，然后经过某些指定的处理，返回数据库厂商标识
   *
   * @param dataSource 数据源
   * @return
   * @throws SQLException
   */
  String getDatabaseId(DataSource dataSource) throws SQLException;
}
