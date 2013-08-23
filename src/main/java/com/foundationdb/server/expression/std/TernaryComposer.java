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

package com.foundationdb.server.expression.std;

import com.foundationdb.server.error.WrongExpressionArityException;
import com.foundationdb.server.expression.Expression;
import com.foundationdb.server.expression.ExpressionComposer;
import com.foundationdb.server.expression.ExpressionType;
import java.util.List;

public abstract class TernaryComposer implements ExpressionComposer
{
    protected abstract Expression doCompose(List<? extends Expression> arguments, List<ExpressionType> typesList);
//    protected abstract ExpressionType composeType(ExpressionType first, ExpressionType second, ExpressionType third);

    @Override
    public Expression compose(List<? extends Expression> arguments, List<ExpressionType> typesList)
    {
        if (arguments.size() != 3)
            throw new WrongExpressionArityException(3, arguments.size());
        if (arguments.size() + 1 != typesList.size())
            throw new IllegalArgumentException("unexpected argc");
        return doCompose(arguments, typesList);
    }

    // For most expressions, NULL is contaminating
    // Any expressions that treat NULL specially should override this
    @Override
    public NullTreating getNullTreating()
    {
        return NullTreating.RETURN_NULL;
    }
}
