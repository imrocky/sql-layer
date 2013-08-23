/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.foundationdb.server.store;

import com.foundationdb.server.service.tree.TreeLink;
import com.foundationdb.KeyValue;
import com.persistit.Key;
import com.persistit.Value;

import java.util.Iterator;

class FDBStoreData {
    final TreeLink link;
    final Key key;
    Value persistitValue;
    byte[] value;
    Iterator<KeyValue> it;

    public FDBStoreData(TreeLink link, Key key) {
        this.link = link;
        this.key = key;
    }
}
