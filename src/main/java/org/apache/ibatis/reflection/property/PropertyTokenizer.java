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
package org.apache.ibatis.reflection.property;

import java.util.Iterator;

/**
 * Property标记器
 *
 * eg: fullname="user[1].linkman.name"
 *
 * @author Clinton Begin
 */
public class PropertyTokenizer implements Iterator<PropertyTokenizer> {

    // name="user"
    private String name;

    // indexedName="user[1]"
    private String indexedName;

    // index="1"
    private String index;

    // children="indexedName"
    private String children;

    /**
     * 属性标记器
     *
     * 例1: 参数: fullname=user[1].linkman.name
     *     children=linkman.name
     *     indexedName=user[1]
     *     name=user
     *     index=1
     * 例2: 参数: fullname=user
     *     children=null
     *     indexedName=user
     *     name=user
     *     index=null
     */
    // eg: fullname="user[1].linkman.name"
    public PropertyTokenizer(String fullname) {
        int delim = fullname.indexOf('.'); // eg: delim=7
        if (delim > -1) {
            name = fullname.substring(0, delim); // name="user[1]"
            children = fullname.substring(delim + 1); // children="linkman.name"
        } else {
            name = fullname;
            children = null;
        }
        indexedName = name; // eg: indexedName="user[1]"
        delim = name.indexOf('['); // delim=4
        if (delim > -1) {
            index = name.substring(delim + 1, name.length() - 1); // index="1"
            name = name.substring(0, delim); // name="user"
        }
    }

    public String getName() {
        return name;
    }

    public String getIndex() {
        return index;
    }

    public String getIndexedName() {
        return indexedName;
    }

    public String getChildren() {
        return children;
    }

    @Override
    public boolean hasNext() {
        return children != null;
    }

    @Override
    public PropertyTokenizer next() {
        return new PropertyTokenizer(children);
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException(
                "Remove is not supported, as it has no meaning in the context of properties.");
    }
}
