/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.restrictions;

import java.nio.ByteBuffer;
import java.util.*;

import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.cql3.statements.Bound;
import org.apache.cassandra.db.IndexExpression;
import org.apache.cassandra.db.composites.CBuilder;
import org.apache.cassandra.db.composites.CType;
import org.apache.cassandra.db.composites.Composite;
import org.apache.cassandra.db.composites.Composite.EOC;
import org.apache.cassandra.db.composites.Composites;
import org.apache.cassandra.db.composites.CompositesBuilder;
import org.apache.cassandra.db.index.SecondaryIndexManager;
import org.apache.cassandra.exceptions.InvalidRequestException;

import static org.apache.cassandra.cql3.statements.RequestValidations.checkFalse;
import static org.apache.cassandra.cql3.statements.RequestValidations.checkNotNull;
import static org.apache.cassandra.cql3.statements.RequestValidations.checkTrue;

/**
 * A set of single column restrictions on a primary key part (partition key or clustering key).
 */
final class SingleColumnPrimaryKeyRestrictions extends AbstractPrimaryKeyRestrictions
{
    /**
     * The restrictions.
     */
    private final SingleColumnRestrictions restrictions;

    /**
     * <code>true</code> if the restrictions are corresponding to an EQ, <code>false</code> otherwise.
     */
    private boolean eq;

    /**
     * <code>true</code> if the restrictions are corresponding to an IN, <code>false</code> otherwise.
     */
    private boolean in;

    /**
     * <code>true</code> if the restrictions are corresponding to a Slice, <code>false</code> otherwise.
     */
    private boolean slice;

    /**
     * <code>true</code> if the restrictions are corresponding to a Contains, <code>false</code> otherwise.
     */
    private boolean contains;

    public SingleColumnPrimaryKeyRestrictions(CType ctype)
    {
        super(ctype);
        this.restrictions = new SingleColumnRestrictions();
        this.eq = true;
    }

    private SingleColumnPrimaryKeyRestrictions(SingleColumnPrimaryKeyRestrictions primaryKeyRestrictions,
                                               SingleColumnRestriction restriction) throws InvalidRequestException
    {
        super(primaryKeyRestrictions.ctype);
        this.restrictions = primaryKeyRestrictions.restrictions.addRestriction(restriction);

        if (!primaryKeyRestrictions.isEmpty())
        {
            ColumnDefinition lastColumn = primaryKeyRestrictions.restrictions.lastColumn();
            ColumnDefinition newColumn = restriction.getColumnDef();

            checkFalse(primaryKeyRestrictions.isSlice() && newColumn.position() > lastColumn.position(),
                       "Clustering column \"%s\" cannot be restricted (preceding column \"%s\" is restricted by a non-EQ relation)",
                       newColumn.name,
                       lastColumn.name);

            if (newColumn.position() < lastColumn.position())
                checkFalse(restriction.isSlice(),
                           "PRIMARY KEY column \"%s\" cannot be restricted (preceding column \"%s\" is restricted by a non-EQ relation)",
                           restrictions.nextColumn(newColumn).name,
                           newColumn.name);
        }

        if (restriction.isSlice() || primaryKeyRestrictions.isSlice())
            this.slice = true;
        else if (restriction.isContains() || primaryKeyRestrictions.isContains())
            this.contains = true;
        else if (restriction.isIN())
            this.in = true;
        else
            this.eq = true;
    }

    @Override
    public boolean isSlice()
    {
        return slice;
    }

    @Override
    public boolean isEQ()
    {
        return eq;
    }

    @Override
    public boolean isIN()
    {
        return in;
    }

    @Override
    public boolean isOnToken()
    {
        return false;
    }

    @Override
    public boolean isContains()
    {
        return contains;
    }

    @Override
    public boolean isMultiColumn()
    {
        return false;
    }

    @Override
    public boolean usesFunction(String ksName, String functionName)
    {
        return restrictions.usesFunction(ksName, functionName);
    }

    @Override
    public PrimaryKeyRestrictions mergeWith(Restriction restriction) throws InvalidRequestException
    {
        if (restriction.isMultiColumn())
        {
            checkTrue(isEmpty(),
                      "Mixing single column relations and multi column relations on clustering columns is not allowed");
            return (PrimaryKeyRestrictions) restriction;
        }

        if (restriction.isOnToken())
        {
            if (isEmpty())
                return (PrimaryKeyRestrictions) restriction;

            return new TokenFilter(this, (TokenRestriction) restriction);
        }

        return new SingleColumnPrimaryKeyRestrictions(this, (SingleColumnRestriction) restriction);
    }

    @Override
    public List<Composite> valuesAsComposites(QueryOptions options) throws InvalidRequestException
    {
        CompositesBuilder builder = new CompositesBuilder(ctype.builder(), ctype);
        for (ColumnDefinition def : restrictions.getColumnDefs())
        {
            Restriction r = restrictions.getRestriction(def);
            assert !r.isSlice();

            List<ByteBuffer> values = r.values(options);

            if (values.isEmpty())
                return Collections.emptyList();

            builder.addEachElementToAll(values);
            checkFalse(builder.containsNull(), "Invalid null value for column %s", def.name);
        }

        return builder.build();
    }

    @Override
    public List<Composite> boundsAsComposites(Bound bound, QueryOptions options) throws InvalidRequestException
    {
        CBuilder builder = ctype.builder();
        List<ColumnDefinition> defs = new ArrayList<>(restrictions.getColumnDefs());

        CompositesBuilder compositeBuilder = new CompositesBuilder(builder, ctype);
        // The end-of-component of composite doesn't depend on whether the
        // component type is reversed or not (i.e. the ReversedType is applied
        // to the component comparator but not to the end-of-component itself),
        // it only depends on whether the slice is reversed
        int keyPosition = 0;
        for (ColumnDefinition def : defs)
        {
            // In a restriction, we always have Bound.START < Bound.END for the "base" comparator.
            // So if we're doing a reverse slice, we must inverse the bounds when giving them as start and end of the slice filter.
            // But if the actual comparator itself is reversed, we must inversed the bounds too.
            Bound b = !def.isReversedType() ? bound : bound.reverse();
            Restriction r = restrictions.getRestriction(def);
            if (keyPosition != def.position() || r.isContains())
                return compositeBuilder.buildWithEOC(bound.isEnd() ? EOC.END : EOC.START);

            if (r.isSlice())
            {
                if (!r.hasBound(b))
                {
                    // There wasn't any non EQ relation on that key, we select all records having the preceding component as prefix.
                    // For composites, if there was preceding component and we're computing the end, we must change the last component
                    // End-Of-Component, otherwise we would be selecting only one record.
                    return compositeBuilder.buildWithEOC(bound.isEnd() ? EOC.END : EOC.START);
                }

                ByteBuffer value = checkNotNull(r.bounds(b, options).get(0), "Invalid null clustering key part %s", r);
                compositeBuilder.addElementToAll(value);
                Composite.EOC eoc = eocFor(r, bound, b);
                return compositeBuilder.buildWithEOC(eoc);
            }

            List<ByteBuffer> values = r.values(options);

            if (values.isEmpty())
                return Collections.emptyList();

            compositeBuilder.addEachElementToAll(values);

            checkFalse(compositeBuilder.containsNull(), "Invalid null clustering key part %s", def.name);
            keyPosition++;
        }
        // Means no relation at all or everything was an equal
        // Note: if the builder is "full", there is no need to use the end-of-component bit. For columns selection,
        // it would be harmless to do it. However, we use this method got the partition key too. And when a query
        // with 2ndary index is done, and with the the partition provided with an EQ, we'll end up here, and in that
        // case using the eoc would be bad, since for the random partitioner we have no guarantee that
        // prefix.end() will sort after prefix (see #5240).
        EOC eoc = !compositeBuilder.hasRemaining() ? EOC.NONE : (bound.isEnd() ? EOC.END : EOC.START);
        return compositeBuilder.buildWithEOC(eoc);
    }

    @Override
    public List<ByteBuffer> values(QueryOptions options) throws InvalidRequestException
    {
        return Composites.toByteBuffers(valuesAsComposites(options));
    }

    @Override
    public List<ByteBuffer> bounds(Bound b, QueryOptions options) throws InvalidRequestException
    {
        return Composites.toByteBuffers(boundsAsComposites(b, options));
    }

    private static Composite.EOC eocFor(Restriction r, Bound eocBound, Bound inclusiveBound)
    {
        if (eocBound.isStart())
            return r.isInclusive(inclusiveBound) ? Composite.EOC.NONE : Composite.EOC.END;

        return r.isInclusive(inclusiveBound) ? Composite.EOC.END : Composite.EOC.START;
    }

    @Override
    public boolean hasBound(Bound b)
    {
        if (isEmpty())
            return false;
        return restrictions.lastRestriction().hasBound(b);
    }

    @Override
    public boolean isInclusive(Bound b)
    {
        if (isEmpty())
            return false;
        return restrictions.lastRestriction().isInclusive(b);
    }

    @Override
    public boolean hasSupportingIndex(SecondaryIndexManager indexManager)
    {
        return restrictions.hasSupportingIndex(indexManager);
    }

    @Override
    public void addIndexExpressionTo(List<IndexExpression> expressions,
                                     SecondaryIndexManager indexManager,
                                     QueryOptions options) throws InvalidRequestException
    {
        Boolean clusteringColumns = null;
        int position = 0;

        for (ColumnDefinition columnDef : restrictions.getColumnDefs())
        {
            // SingleColumnPrimaryKeyRestrictions contains only one kind of column, either partition key or clustering columns.
            // Therefore we only need to check the column kind once. All the other columns will be of the same kind.
            if (clusteringColumns == null)
                clusteringColumns = columnDef.isClusteringColumn() ? Boolean.TRUE : Boolean.FALSE;

            Restriction restriction = restrictions.getRestriction(columnDef);

            // We ignore all the clustering columns that can be handled by slices.
            if (clusteringColumns && !restriction.isContains()&& position == columnDef.position())
            {
                position++;
                if (!restriction.hasSupportingIndex(indexManager))
                    continue;
            }
            restriction.addIndexExpressionTo(expressions, indexManager, options);
        }
    }

    @Override
    public Collection<ColumnDefinition> getColumnDefs()
    {
        return restrictions.getColumnDefs();
    }
}
