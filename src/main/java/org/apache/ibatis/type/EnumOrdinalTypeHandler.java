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
package org.apache.ibatis.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 针对Enum枚举类型进行类型转换处理，针对【枚举的ordinal序数】进行转换处理
 * <p>
 * IN操作：根据Enum的数组下标（从0开始），存入DB
 * OUT操作：根据DB的int值，作为下标，从数组中取出Enum实例对象
 *
 * @author Clinton Begin
 * @modify muse
 */
public class EnumOrdinalTypeHandler<E extends Enum<E>> extends BaseTypeHandler<E> {

    private Class<E> type;
    private final E[] enums;

    /**
     * 构造方法中，将枚举类型，转换为数组存储枚举对象
     *
     * @param type 枚举Class对象
     */
    public EnumOrdinalTypeHandler(Class<E> type) {
        if (type == null) {
            throw new IllegalArgumentException("Type argument cannot be null");
        }
        this.type = type;
        this.enums = type.getEnumConstants(); // 获得以数组形式返回的所有枚举值
        if (this.enums == null) {
            throw new IllegalArgumentException(type.getSimpleName() + " does not represent an enum type.");
        }
    }

    /**
     * 将传入的enum对象的序数，入DB
     *
     * @param ps        PreparedStatement 预处理语句,提供IN参数 （查询前拼装的查询语句）
     * @param i
     * @param parameter
     * @param jdbcType
     *
     * @throws SQLException
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, E parameter, JdbcType jdbcType) throws SQLException {
        /**
         * Enum类提供了一个ordinal()方法，用来返回枚举对象的序数，从0开始
         * eg:
         *  CustCheckEnum.class.getEnumConstants()[2].ordinal() 返回2；
         */
        ps.setInt(i, parameter.ordinal());
    }

    /**
     * 根据结果集中取出的列名称为columnName的值，如果是int类型的，那么从enum数组中，获得enum所定义的第i个enum实例。
     *
     * @param rs
     * @param columnName
     *
     * @return
     *
     * @throws SQLException
     */
    @Override
    public E getNullableResult(ResultSet rs, String columnName) throws SQLException {
        int i = rs.getInt(columnName);
        if (rs.wasNull()) {
            return null;
        } else {
            try {
                return enums[i];
            } catch (Exception ex) {
                throw new IllegalArgumentException(
                        "Cannot convert " + i + " to " + type.getSimpleName() + " by ordinal value.", ex);
            }
        }
    }

    /**
     * 根据结果集中取出的第columnIndex列的值，如果是int类型的，那么从enum数组中，获得enum所定义的第i个enum实例。
     *
     * @param rs          ResultSet 结果集 （查询后的结果）
     * @param columnIndex
     *
     * @return
     *
     * @throws SQLException
     */
    @Override
    public E getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        int i = rs.getInt(columnIndex);
        if (rs.wasNull()) {
            return null;
        } else {
            try {
                return enums[i];
            } catch (Exception ex) {
                throw new IllegalArgumentException(
                        "Cannot convert " + i + " to " + type.getSimpleName() + " by ordinal value.", ex);
            }
        }
    }

    /**
     * 将传入的enum对象的序数，入DB
     *
     * @param ps CallableStatement 预处理语句,提供IN/OUT参数 （查询前拼装的查询语句）
     * @param i
     *
     * @throws SQLException
     */
    @Override
    public E getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        int i = cs.getInt(columnIndex);
        if (cs.wasNull()) {
            return null;
        } else {
            try {
                return enums[i];
            } catch (Exception ex) {
                throw new IllegalArgumentException(
                        "Cannot convert " + i + " to " + type.getSimpleName() + " by ordinal value.", ex);
            }
        }
    }

}
