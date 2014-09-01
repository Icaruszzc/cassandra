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
package org.apache.cassandra.db.filter;

import java.io.DataInput;
import java.io.IOException;
import java.util.*;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.rows.*;
import org.apache.cassandra.db.partitions.*;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.util.DataOutputPlus;
import org.apache.cassandra.utils.SearchIterator;

/**
 * A filter selecting rows given their clustering value.
 */
public class ClusteringIndexNamesFilter extends AbstractClusteringIndexFilter
{
    static final InternalDeserializer deserializer = new NamesDeserializer();

    // This could be empty if selectedColumns only has static columns (in which case the filter still
    // selects the static row)
    private final NavigableSet<Clustering> clusterings;

    // clusterings is always in clustering order (because we need it that way in some methods), but we also
    // sometimes need those clustering in "query" order (i.e. in reverse clustering order if the query is
    // reversed), so we keep that too for simplicity.
    private final NavigableSet<Clustering> clusteringsInQueryOrder;

    public ClusteringIndexNamesFilter(NavigableSet<Clustering> clusterings, boolean reversed)
    {
        super(reversed);
        assert !clusterings.contains(Clustering.STATIC_CLUSTERING);
        this.clusterings = clusterings;
        this.clusteringsInQueryOrder = reversed ? clusterings.descendingSet() : clusterings;
    }

    /**
     * The set of requested rows.
     *
     * Please note that this can be empty if only the static row is requested.
     *
     * @return the set of requested clustering in clustering order (note that
     * this is always in clustering order even if the query is reversed).
     */
    public NavigableSet<Clustering> requestedRows()
    {
        return clusterings;
    }

    public boolean selectsAllPartition()
    {
        return false;
    }

    public boolean selects(Clustering clustering)
    {
        return clusterings.contains(clustering);
    }

    public ClusteringIndexNamesFilter forPaging(ClusteringComparator comparator, Clustering lastReturned, boolean inclusive)
    {
        // TODO: Consider removal of the initial check.
        int cmp = comparator.compare(lastReturned, clusteringsInQueryOrder.first());
        if (cmp < 0 || (inclusive && cmp == 0))
            return this;

        NavigableSet<Clustering> newClusterings = reversed ?
                                                  clusterings.headSet(lastReturned, inclusive) :
                                                  clusterings.tailSet(lastReturned, inclusive);

        return new ClusteringIndexNamesFilter(newClusterings, reversed);
    }

    public boolean isFullyCoveredBy(CachedPartition partition)
    {
        // 'partition' contains all columns, so it covers our filter if our last clusterings
        // is smaller than the last in the cache
        return clusterings.comparator().compare(clusterings.last(), partition.lastRow().clustering()) <= 0;
    }

    public boolean isHeadFilter()
    {
        return false;
    }

    // Given another iterator, only return the rows that match this filter
    public UnfilteredRowIterator filterNotIndexed(ColumnFilter columnFilter, UnfilteredRowIterator iterator)
    {
        // Note that we don't filter markers because that's a bit trickier (we don't know in advance until when
        // the range extend) and it's harmless to left them.
        return new FilteringRowIterator(iterator)
        {
            @Override
            public FilteringRow makeRowFilter()
            {
                return FilteringRow.columnsFilteringRow(columnFilter);
            }

            @Override
            protected boolean includeRow(Row row)
            {
                return clusterings.contains(row.clustering());
            }
        };
    }

    public UnfilteredRowIterator filter(final SliceableUnfilteredRowIterator iter)
    {
        // Please note that this method assumes that rows from 'iter' already have their columns filtered, i.e. that
        // they only include columns that we select.
        return new WrappingUnfilteredRowIterator(iter)
        {
            private final Iterator<Clustering> clusteringIter = clusteringsInQueryOrder.iterator();
            private Iterator<Unfiltered> currentClustering;
            private Unfiltered next;

            @Override
            public boolean hasNext()
            {
                if (next != null)
                    return true;

                if (currentClustering != null && currentClustering.hasNext())
                {
                    next = currentClustering.next();
                    return true;
                }

                while (clusteringIter.hasNext())
                {
                    Clustering nextClustering = clusteringIter.next();
                    currentClustering = iter.slice(Slice.make(nextClustering));
                    if (currentClustering.hasNext())
                    {
                        next = currentClustering.next();
                        return true;
                    }
                }
                return false;
            }

            @Override
            public Unfiltered next()
            {
                if (next == null && !hasNext())
                    throw new NoSuchElementException();

                Unfiltered toReturn = next;
                next = null;
                return toReturn;
            }
        };
    }

    public UnfilteredRowIterator getUnfilteredRowIterator(final ColumnFilter columnFilter, final Partition partition)
    {
        final SearchIterator<Clustering, Row> searcher = partition.searchIterator(columnFilter, reversed);
        return new AbstractUnfilteredRowIterator(partition.metadata(),
                                        partition.partitionKey(),
                                        partition.partitionLevelDeletion(),
                                        columnFilter.fetchedColumns(),
                                        searcher.next(Clustering.STATIC_CLUSTERING),
                                        reversed,
                                        partition.stats())
        {
            private final Iterator<Clustering> clusteringIter = clusteringsInQueryOrder.iterator();

            protected Unfiltered computeNext()
            {
                while (clusteringIter.hasNext() && searcher.hasNext())
                {
                    Row row = searcher.next(clusteringIter.next());
                    if (row != null)
                        return row;
                }
                return endOfData();
            }
        };
    }

    public boolean shouldInclude(SSTableReader sstable)
    {
        // TODO: we could actually exclude some sstables
        return true;
    }

    public String toString(CFMetaData metadata)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("names(");
        int i = 0;
        for (Clustering clustering : clusterings)
            sb.append(i++ == 0 ? "" : ", ").append(clustering.toString(metadata));
        if (reversed)
            sb.append(", reversed");
        return sb.append(")").toString();
    }

    public String toCQLString(CFMetaData metadata)
    {
        if (clusterings.isEmpty())
            return "";

        StringBuilder sb = new StringBuilder();
        sb.append("(").append(ColumnDefinition.toCQLString(metadata.clusteringColumns())).append(")");
        sb.append(clusterings.size() == 1 ? " = " : " IN (");
        int i = 0;
        for (Clustering clustering : clusterings)
            sb.append(i++ == 0 ? "" : ", ").append("(").append(clustering.toCQLString(metadata)).append(")");
        sb.append(clusterings.size() == 1 ? "" : ")");

        appendOrderByToCQLString(metadata, sb);
        return sb.toString();
    }

    Kind kind()
    {
        return Kind.NAMES;
    }

    protected void serializeInternal(DataOutputPlus out, int version) throws IOException
    {
        ClusteringComparator comparator = (ClusteringComparator)clusterings.comparator();
        out.writeInt(clusterings.size());
        for (Clustering clustering : clusterings)
            Clustering.serializer.serialize(clustering, out, version, comparator.subtypes());
    }

    protected long serializedSizeInternal(int version, TypeSizes sizes)
    {
        long size = 0;
        ClusteringComparator comparator = (ClusteringComparator)clusterings.comparator();
        for (Clustering clustering : clusterings)
            size += Clustering.serializer.serializedSize(clustering, version, comparator.subtypes(), sizes);
        return size;
    }

    private static class NamesDeserializer extends InternalDeserializer
    {
        public ClusteringIndexFilter deserialize(DataInput in, int version, CFMetaData metadata, boolean reversed) throws IOException
        {
            ClusteringComparator comparator = metadata.comparator;
            NavigableSet<Clustering> clusterings = new TreeSet<>(comparator);
            int size = in.readInt();
            for (int i = 0; i < size; i++)
                clusterings.add(Clustering.serializer.deserialize(in, version, comparator.subtypes()).takeAlias());

            return new ClusteringIndexNamesFilter(clusterings, reversed);
        }
    }
}