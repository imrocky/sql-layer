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

package com.foundationdb.server.store.format;

import com.foundationdb.ais.model.HasStorage;
import com.foundationdb.ais.protobuf.AISProtobuf.Storage;
import com.foundationdb.ais.protobuf.FDBProtobuf;

public class FDBStorageFormat extends StorageFormat<FDBStorageDescription>
{
    private FDBStorageFormat() {
    }

    public static void register(StorageFormatRegistry registry) {
        registry.registerStorageFormat(FDBProtobuf.prefixBytes, null, FDBStorageDescription.class, new FDBStorageFormat());
    }

    public FDBStorageDescription readProtobuf(Storage pbStorage, HasStorage forObject, FDBStorageDescription storageDescription) {
        if (storageDescription == null) {
            storageDescription = new FDBStorageDescription(forObject);
        }
        storageDescription.setPrefixBytes(pbStorage.getExtension(FDBProtobuf.prefixBytes).toByteArray());
        return storageDescription;
    }
}
