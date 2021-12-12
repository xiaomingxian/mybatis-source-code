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
package org.apache.ibatis.executor;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

/**
 * 简单执行器
 *
 * @author Clinton Begin
 */
public class SimpleExecutor extends BaseExecutor {

    public SimpleExecutor(Configuration configuration, Transaction transaction) {
        super(configuration, transaction);
    }

    @Override
    public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
        Statement stmt = null;
        try {
            Configuration configuration = ms.getConfiguration();
            StatementHandler handler = configuration.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null
                    , null);
            stmt = prepareStatement(handler, ms.getStatementLog());
            return handler.update(stmt);
        } finally {
            closeStatement(stmt);
        }
    }

    // eg1: parameter = {"id": 2L, "param1", 2L}  rowBounds = new RowBounds() resultHandler = null
    @Override
    public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler,
                               BoundSql boundSql) throws SQLException {
        Statement stmt = null;
        try {
            Configuration configuration = ms.getConfiguration();
            /** 根据Configuration来构建StatementHandler */
            StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds,
                    resultHandler, boundSql);

            // eg1: handler=RoutingStatementHandler
            /** 然后使用prepareStatement方法，对SQL进行预编译并设置入参 */
            stmt = prepareStatement(handler, ms.getStatementLog());

            // eg1: handler=RoutingStatementHandler parameter = {"id": 2L, "param1", 2L}  rowBounds = new RowBounds() resultHandler = null
            /** 开始执行真正的查询操作。将包装好的Statement通过StatementHandler来执行，并把结果传递给resultHandler */
            return handler.<E>query(stmt, resultHandler);
        } finally {
            closeStatement(stmt);
        }
    }

    @Override
    protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
            throws SQLException {
        Configuration configuration = ms.getConfiguration();
        StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
        Statement stmt = prepareStatement(handler, ms.getStatementLog());
        return handler.<E>queryCursor(stmt);
    }

    @Override
    public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
        return Collections.emptyList();
    }

    /**
     * 使用prepareStatement方法，对SQL编译并设置入参
     *
     * @param handler
     * @param statementLog
     * @return
     * @throws SQLException
     */
    // eg1: handler=RoutingStatementHandler
    private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
        Statement stmt;

        /** 获得Connection实例 */
        Connection connection = getConnection(statementLog);

        // eg1: handler=RoutingStatementHandler
        /** 第一步：调用了StatementHandler的prepared进行了【sql的预编译】 */
        stmt = handler.prepare(connection, transaction.getTimeout());

        /** 第二步：通过PreparedStatementHandler的parameterize来给【sql设置入参】 */
        handler.parameterize(stmt);

        // eg1: 返回org.apache.ibatis.logging.jdbc.PreparedStatementLogger@2e570ded
        return stmt;
    }

}
