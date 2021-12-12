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
package org.apache.ibatis.scripting.xmltags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Clinton Begin
 * @modify muse
 */
public class XMLScriptBuilder extends BaseBuilder {

    private XNode context;
    private boolean isDynamic;
    private Class<?> parameterType;

    public XMLScriptBuilder(Configuration configuration, XNode context) {
        this(configuration, context, null);
    }

    public XMLScriptBuilder(Configuration configuration, XNode context, Class<?> parameterType) {
        super(configuration);
        this.context = context;
        this.parameterType = parameterType;
    }

    public SqlSource parseScriptNode() {
        List<SqlNode> contents = parseDynamicTags(context);
        MixedSqlNode rootSqlNode = new MixedSqlNode(contents);
        SqlSource sqlSource = null;
        if (isDynamic) {
            sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
        } else {
            sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
        }
        return sqlSource;
    }

    List<SqlNode> parseDynamicTags(XNode node) {
        List<SqlNode> contents = new ArrayList<SqlNode>();
        NodeList children = node.getNode().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            XNode child = node.newXNode(children.item(i));
            if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE
                    || child.getNode().getNodeType() == Node.TEXT_NODE) {
                String data = child.getStringBody("");
                TextSqlNode textSqlNode = new TextSqlNode(data);
                if (textSqlNode.isDynamic()) {
                    contents.add(textSqlNode);
                    isDynamic = true;
                } else {
                    contents.add(new StaticTextSqlNode(data));
                }
            } else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628
                String nodeName = child.getNode().getNodeName();
                NodeHandler handler = nodeHandlers(nodeName);
                if (handler == null) {
                    throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
                }
                handler.handleNode(child, contents);
                isDynamic = true;
            }
        }
        return contents;
    }

    NodeHandler nodeHandlers(String nodeName) {
        Map<String, NodeHandler> map = new HashMap<String, NodeHandler>();

        /**
         * trim意味着要去掉一些特殊的字符
         *
         * prefix——代表语句的前缀
         * prefixOverrides——代表需要去掉trim里拼装后的sql中，前缀部分的某些字符串
         * suffix——代表语句的后缀
         * prefixOverrides——代表需要去掉trim里拼装后的sql中，后缀部分的某些字符串
         *
         * 1>>>>
         * select * from user
         *　　<trim prefix="WHERE" prefixoverride="AND | OR">
         *　　　　<if test="name != null and name.length()>0"> AND name=#{name}</if>
         *　　　　<if test="gender != null and gender.length()>0"> AND gender=#{gender}</if>
         *　　</trim>
         *　　假如说name和gender的值都不为null的话打印的SQL为：select * from user WHERE［去掉最前面的AND或者OR］name = 'xx' AND gender = 'xx'
         *
         * 2>>>>
         * update user
         *　　<trim prefix="set" suffixoverride="," suffix=" where id = #{id} ">
         *　　　　<if test="name != null and name.length()>0"> name=#{name} , </if>
         *　　　　<if test="gender != null and gender.length()>0"> gender=#{gender} ,  </if>
         *　　</trim>
         *　假如说name和gender的值都不为null的话打印的SQL为：update user set name='xx' , gender='xx' [去掉最后一个逗号] where id = 'x'
         */
        map.put("trim", new TrimHandler());

        /**
         * 用于拼装where条件语句
         *
         * 使用<where>...</where>可以优化where 1=1的书写方式，会自动拼装and在where判断的位置
         */
        map.put("where", new WhereHandler());

        /**
         * 用于拼装update操作SQL
         *
         * <update id="updateRole" parameterType="role">
         *    update t_role
         *    <set>
         *        <if test="roleName != null and roleName != ''">
         *            role_name = #{roleName},  <!-- set元素遇到了逗号，它会把对应的逗号去掉>
         *        </if>
         *        <if test="note != null and note !=''">
         *            note = #{note}
         *        </if>
         *    </set>
         *    where role_no = #{roleNo}
         * </update>
         */
        map.put("set", new SetHandler());

        /**
         * 用户拼装in等操作SQL，循环传参集合
         *
         * collection——传入进来的集合参数名称，可以是数组，list或者Set等集合
         * item 配置的循环中当前的元素
         *
         * <select id="findUserBySex" resultType="user">
         *     select * from t_user where sex in
         *        <foreach item="sex" collection="sexList" open="(" separator="," close=")">
         *            #{sex}
         *        </foreach>
         * </select>
         */
        map.put("foreach", new ForEachHandler());

        /**
         * 动态SQL中最常用的判断语句
         *
         * <if test="roleName != null and roleName != ''">
         *     and role_name like concat('%', #{roleName}, '%')
         * </if>
         */
        map.put("if", new IfHandler());

        /**
         * 实现switch...case...default语句表达方式
         *
         * <choose>
         *    <when test="roleNo != null and roleNo != ''">
         *        and role_no = #{roleNo}
         *    </when>
         *    <when test="roleName != null and roleName != ''">
         *        and role_name = #{roleName}
         *    </when>
         *    <otherwise>
         *        and note is not null
         *    </otherwise>
         * </choose>
         */
        map.put("choose", new ChooseHandler());
        map.put("when", new IfHandler());
        map.put("otherwise", new OtherwiseHandler());

        /**
         * 通过OGNL表达式去自定义一个上下文变量。
         */
        map.put("bind", new BindHandler());
        return map.get(nodeName);
    }

    private interface NodeHandler {
        void handleNode(XNode nodeToHandle, List<SqlNode> targetContents);
    }

    private class BindHandler implements NodeHandler {
        public BindHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            final String name = nodeToHandle.getStringAttribute("name");
            final String expression = nodeToHandle.getStringAttribute("value");
            final VarDeclSqlNode node = new VarDeclSqlNode(name, expression);
            targetContents.add(node);
        }
    }

    private class TrimHandler implements NodeHandler {
        public TrimHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            List<SqlNode> contents = parseDynamicTags(nodeToHandle);
            MixedSqlNode mixedSqlNode = new MixedSqlNode(contents);
            String prefix = nodeToHandle.getStringAttribute("prefix");
            String prefixOverrides = nodeToHandle.getStringAttribute("prefixOverrides");
            String suffix = nodeToHandle.getStringAttribute("suffix");
            String suffixOverrides = nodeToHandle.getStringAttribute("suffixOverrides");
            TrimSqlNode trim =
                    new TrimSqlNode(configuration, mixedSqlNode, prefix, prefixOverrides, suffix, suffixOverrides);
            targetContents.add(trim);
        }
    }

    private class WhereHandler implements NodeHandler {
        public WhereHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            List<SqlNode> contents = parseDynamicTags(nodeToHandle);
            MixedSqlNode mixedSqlNode = new MixedSqlNode(contents);
            WhereSqlNode where = new WhereSqlNode(configuration, mixedSqlNode);
            targetContents.add(where);
        }
    }

    private class SetHandler implements NodeHandler {
        public SetHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            List<SqlNode> contents = parseDynamicTags(nodeToHandle);
            MixedSqlNode mixedSqlNode = new MixedSqlNode(contents);
            SetSqlNode set = new SetSqlNode(configuration, mixedSqlNode);
            targetContents.add(set);
        }
    }

    private class ForEachHandler implements NodeHandler {
        public ForEachHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            List<SqlNode> contents = parseDynamicTags(nodeToHandle);
            MixedSqlNode mixedSqlNode = new MixedSqlNode(contents);
            String collection = nodeToHandle.getStringAttribute("collection");
            String item = nodeToHandle.getStringAttribute("item");
            String index = nodeToHandle.getStringAttribute("index");
            String open = nodeToHandle.getStringAttribute("open");
            String close = nodeToHandle.getStringAttribute("close");
            String separator = nodeToHandle.getStringAttribute("separator");
            ForEachSqlNode forEachSqlNode =
                    new ForEachSqlNode(configuration, mixedSqlNode, collection, index, item, open, close, separator);
            targetContents.add(forEachSqlNode);
        }
    }

    private class IfHandler implements NodeHandler {
        public IfHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            List<SqlNode> contents = parseDynamicTags(nodeToHandle);
            MixedSqlNode mixedSqlNode = new MixedSqlNode(contents);
            String test = nodeToHandle.getStringAttribute("test");
            IfSqlNode ifSqlNode = new IfSqlNode(mixedSqlNode, test);
            targetContents.add(ifSqlNode);
        }
    }

    private class OtherwiseHandler implements NodeHandler {
        public OtherwiseHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            List<SqlNode> contents = parseDynamicTags(nodeToHandle);
            MixedSqlNode mixedSqlNode = new MixedSqlNode(contents);
            targetContents.add(mixedSqlNode);
        }
    }

    private class ChooseHandler implements NodeHandler {
        public ChooseHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            List<SqlNode> whenSqlNodes = new ArrayList<SqlNode>();
            List<SqlNode> otherwiseSqlNodes = new ArrayList<SqlNode>();
            handleWhenOtherwiseNodes(nodeToHandle, whenSqlNodes, otherwiseSqlNodes);
            SqlNode defaultSqlNode = getDefaultSqlNode(otherwiseSqlNodes);
            ChooseSqlNode chooseSqlNode = new ChooseSqlNode(whenSqlNodes, defaultSqlNode);
            targetContents.add(chooseSqlNode);
        }

        private void handleWhenOtherwiseNodes(XNode chooseSqlNode, List<SqlNode> ifSqlNodes,
                                              List<SqlNode> defaultSqlNodes) {
            List<XNode> children = chooseSqlNode.getChildren();
            for (XNode child : children) {
                String nodeName = child.getNode().getNodeName();
                NodeHandler handler = nodeHandlers(nodeName);
                if (handler instanceof IfHandler) {
                    handler.handleNode(child, ifSqlNodes);
                } else if (handler instanceof OtherwiseHandler) {
                    handler.handleNode(child, defaultSqlNodes);
                }
            }
        }

        private SqlNode getDefaultSqlNode(List<SqlNode> defaultSqlNodes) {
            SqlNode defaultSqlNode = null;
            if (defaultSqlNodes.size() == 1) {
                defaultSqlNode = defaultSqlNodes.get(0);
            } else if (defaultSqlNodes.size() > 1) {
                throw new BuilderException("Too many default (otherwise) elements in choose statement.");
            }
            return defaultSqlNode;
        }
    }

}
