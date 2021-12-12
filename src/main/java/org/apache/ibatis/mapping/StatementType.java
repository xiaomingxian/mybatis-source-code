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

/**
 * 在mapper文件中可以使用statementType标记使用什么的对象操作SQL语句。
 * statementType：标记操作SQL的对象
 * 取值说明：
 * 1、STATEMENT:直接操作sql，不进行预编译，获取数据：$—Statement
 * 2、PREPARED:预处理，参数，进行预编译，获取数据：#—–PreparedStatement:默认
 * 3、CALLABLE:执行存储过程————CallableStatement
 *
 * <update id="update4" statementType="STATEMENT">
 *     update tb_car set price=${price} where id=${id}
 * </update>
 *
 * @author Clinton Begin
 */
public enum StatementType {
    STATEMENT, PREPARED, CALLABLE
}
