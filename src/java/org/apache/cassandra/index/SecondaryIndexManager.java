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
package org.apache.cassandra.index;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.primitives.Longs;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.JMXEnabledThreadPoolExecutor;
import org.apache.cassandra.concurrent.NamedThreadFactory;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.cql3.statements.IndexTarget;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.filter.RowFilter;
import org.apache.cassandra.db.lifecycle.SSTableSet;
import org.apache.cassandra.db.lifecycle.View;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.db.rows.*;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.index.internal.CassandraIndex;
import org.apache.cassandra.index.transactions.*;
import org.apache.cassandra.io.sstable.ReducingKeyIterator;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.schema.IndexMetadata;
import org.apache.cassandra.schema.Indexes;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.concurrent.OpOrder;
import org.apache.cassandra.utils.concurrent.Refs;

/**
 * Handles the core maintenance functionality associated with indexes: adding/removing them to or from
 * a table, (re)building during bootstrap or other streaming operations, flushing, reloading metadata
 * and so on.
 *
 * The Index interface defines a number of methods which return Callable<?>. These are primarily the
 * management tasks for an index implementation. Most of them are currently executed in a blocking
 * fashion via submission to SIM's blockingExecutor. This provides the desired behaviour in pretty
 * much all cases, as tasks like flushing an index needs to be executed synchronously to avoid potentially
 * deadlocking on the FlushWriter or PostFlusher. Several of these Callable<?> returning methods on Index could
 * then be defined with as void and called directly from SIM (rather than being run via the executor service).
 * Separating the task defintion from execution gives us greater flexibility though, so that in future, for example,
 * if the flush process allows it we leave open the possibility of executing more of these tasks asynchronously.
 *
 * The primary exception to the above is the Callable returned from Index#addIndexedColumn. This may
 * involve a significant effort, building a new index over any existing data. We perform this task asynchronously;
 * as it is called as part of a schema update, which we do not want to block for a long period. Building non-custom
 * indexes is performed on the CompactionManager.
 *
 * This class also provides instances of processors which listen to updates to the base table and forward to
 * registered Indexes the info required to keep those indexes up to date.
 * There are two variants of these processors, each with a factory method provided by SIM:
 *      IndexTransaction: deals with updates generated on the regular write path.
 *      CleanupTransaction: used when partitions are modified during compaction or cleanup operations.
 * Further details on their usage and lifecycles can be found in the interface definitions below.
 *
 * Finally, the bestIndexFor method is used at query time to identify the most selective index of those able
 * to satisfy any search predicates defined by a ReadCommand's RowFilter. It returns a thin IndexAccessor object
 * which enables the ReadCommand to access the appropriate functions of the Index at various stages in its lifecycle.
 * e.g. the getEstimatedResultRows is required when StorageProxy calculates the initial concurrency factor for
 * distributing requests to replicas, whereas a Searcher instance is needed when the ReadCommand is executed locally on
 * a target replica.
 */
public class SecondaryIndexManager implements IndexRegistry
{
    private static final Logger logger = LoggerFactory.getLogger(SecondaryIndexManager.class);

    private Map<String, Index> indexes = Maps.newConcurrentMap();

    // executes tasks returned by Indexer#addIndexColumn which may require index(es) to be (re)built
    private static final ExecutorService asyncExecutor =
        new JMXEnabledThreadPoolExecutor(1,
                                         StageManager.KEEPALIVE,
                                         TimeUnit.SECONDS,
                                         new LinkedBlockingQueue<>(),
                                         new NamedThreadFactory("SecondaryIndexManagement"),
                                         "internal");

    // executes all blocking tasks produced by Indexers e.g. getFlushTask, getMetadataReloadTask etc
    private static final ExecutorService blockingExecutor = MoreExecutors.newDirectExecutorService();

    /**
     * The underlying column family containing the source data for these indexes
     */
    public final ColumnFamilyStore baseCfs;

    public SecondaryIndexManager(ColumnFamilyStore baseCfs)
    {
        this.baseCfs = baseCfs;
    }


    /**
     * Drops and adds new indexes associated with the underlying CF
     */
    public void reload()
    {
        // figure out what needs to be added and dropped.
        Indexes tableIndexes = baseCfs.metadata.getIndexes();
        indexes.keySet()
               .stream()
               .filter(indexName -> !tableIndexes.has(indexName))
               .forEach(this::removeIndex);

        // we call add for every index definition in the collection as
        // some may not have been created here yet, only added to schema
        for (IndexMetadata tableIndex : tableIndexes)
            addIndex(tableIndex);
    }

    private Future<?> reloadIndex(IndexMetadata indexDef)
    {
        // if the index metadata has changed, reload the index
        IndexMetadata registered = indexes.get(indexDef.name).getIndexMetadata();
        if (!registered.equals(indexDef))
        {
            Index index = indexes.remove(registered.name);
            index.register(this);
            return blockingExecutor.submit(index.getMetadataReloadTask(indexDef));
        }

        // otherwise, nothing to do
        return Futures.immediateFuture(null);
    }

    private Future<?> createIndex(IndexMetadata indexDef)
    {
        Index index = createInstance(indexDef);
        index.register(this);
        final Callable<?> initialBuildTask = index.getInitializationTask();
        return initialBuildTask == null
               ? Futures.immediateFuture(null)
               : asyncExecutor.submit(initialBuildTask);
    }

    /**
     * Adds and builds a index
     * @param indexDef the IndexMetadata describing the index
     */
    public synchronized Future<?> addIndex(IndexMetadata indexDef)
    {
        if (indexes.containsKey(indexDef.name))
            return reloadIndex(indexDef);
        else
            return createIndex(indexDef);
    }

    public synchronized void removeIndex(String indexName)
    {
        Index index = indexes.remove(indexName);
        if (null != index)
        {
            executeBlocking(index.getInvalidateTask());
            unregisterIndex(index);
        }
    }

    /**
     * Called when dropping a Table
     */
    public void markAllIndexesRemoved()
    {
       getBuiltIndexNames().forEach(this::markIndexRemoved);
    }

    /**
    * Does a full, blocking rebuild of the indexes specified by columns from the sstables.
    * Caller must acquire and release references to the sstables used here.
    * Note also that only this method of (re)building indexes:
    *   a) takes a set of index *names* rather than Indexers
    *   b) marks exsiting indexes removed prior to rebuilding
    *
    * @param sstables the data to build from
    * @param indexNames the list of indexes to be rebuilt
    */
    public void rebuildIndexesBlocking(Collection<SSTableReader> sstables, Set<String> indexNames)
    {
        Set<Index> toRebuild = indexes.values().stream()
                                               .filter(indexer -> indexNames.contains(indexer.getIndexName()))
                                               .collect(Collectors.toSet());
        if (toRebuild.isEmpty())
        {
            logger.info("No defined indexes with the supplied names");
            return;
        }

        toRebuild.forEach(indexer -> markIndexRemoved(indexer.getIndexName()));

        buildIndexesBlocking(sstables, toRebuild);

        toRebuild.forEach(indexer -> markIndexBuilt(indexer.getIndexName()));
    }

    public void buildAllIndexesBlocking(Collection<SSTableReader> sstables)
    {
        buildIndexesBlocking(sstables, ImmutableSet.copyOf(indexes.values()));
    }

    // For convenience, may be called directly from Index impls
    public void buildIndexBlocking(Index index)
    {
        try (ColumnFamilyStore.RefViewFragment viewFragment = baseCfs.selectAndReference(View.select(SSTableSet.CANONICAL));
             Refs<SSTableReader> sstables = viewFragment.refs)
        {
            buildIndexesBlocking(sstables, Collections.singleton(index));
            markIndexBuilt(index.getIndexName());
        }
    }

    private void buildIndexesBlocking(Collection<SSTableReader> sstables, Set<Index> indexes)
    {
        if (indexes.isEmpty())
            return;

        logger.info("Submitting index build of {} for data in {}",
                    indexes.stream().map(Index::getIndexName).collect(Collectors.joining(",")),
                    sstables.stream().map(SSTableReader::toString).collect(Collectors.joining(",")));

        SecondaryIndexBuilder builder = new SecondaryIndexBuilder(baseCfs,
                                                                  indexes,
                                                                  new ReducingKeyIterator(sstables));
        Future<?> future = CompactionManager.instance.submitIndexBuild(builder);
        FBUtilities.waitOnFuture(future);

        flushIndexesBlocking(indexes);
        logger.info("Index build of {} complete",
                    indexes.stream().map(Index::getIndexName).collect(Collectors.joining(",")));
    }

    private void markIndexBuilt(String indexName)
    {
        SystemKeyspace.setIndexBuilt(baseCfs.name, indexName);
    }

    private void markIndexRemoved(String indexName)
    {
        SystemKeyspace.setIndexRemoved(baseCfs.name, indexName);
    }


    public Index getIndexByName(String indexName)
    {
        return indexes.get(indexName);
    }

    private Index createInstance(IndexMetadata indexDef)
    {
        Index newIndex;
        if (indexDef.isCustom())
        {
            assert indexDef.options != null;
            String className = indexDef.options.get(IndexTarget.CUSTOM_INDEX_OPTION_NAME);
            assert ! Strings.isNullOrEmpty(className);
            try
            {
                Class<? extends Index> indexClass = FBUtilities.classForName(className, "Index");
                Constructor ctor = indexClass.getConstructor(ColumnFamilyStore.class, IndexMetadata.class);
                newIndex = (Index)ctor.newInstance(baseCfs, indexDef);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
        else
        {
            newIndex = CassandraIndex.newIndex(baseCfs, indexDef);
        }
        return newIndex;
    }

    /**
     * Truncate all indexes
     */
    public void truncateAllIndexesBlocking(final long truncatedAt)
    {
        executeAllBlocking(indexes.values().stream(), (index) -> index.getTruncateTask(truncatedAt));
    }

    /**
     * Remove all indexes
     */
    public void invalidateAllIndexesBlocking()
    {
        executeAllBlocking(indexes.values().stream(), Index::getInvalidateTask);
    }

    /**
     * Perform a blocking flush all indexes
     */
    public void flushAllIndexesBlocking()
    {
       flushIndexesBlocking(ImmutableSet.copyOf(indexes.values()));
    }

    /**
     * Perform a blocking flush of selected indexes
     */
    public void flushIndexesBlocking(Set<Index> indexes)
    {
        if (indexes.isEmpty())
            return;

        List<Future<?>> wait = new ArrayList<>();
        List<Index> nonCfsIndexes = new ArrayList<>();

        // for each CFS backed index, submit a flush task which we'll wait on for completion
        // for the non-CFS backed indexes, we'll flush those while we wait.
        synchronized (baseCfs.getTracker())
        {
            indexes.forEach(index ->
                index.getBackingTable()
                     .map(cfs -> wait.add(cfs.forceFlush()))
                     .orElseGet(() -> nonCfsIndexes.add(index)));
        }

        executeAllBlocking(nonCfsIndexes.stream(), Index::getBlockingFlushTask);
        FBUtilities.waitOnFutures(wait);
    }

    /**
     * Performs a blocking flush of all custom indexes
     */
    public void flushAllNonCFSBackedIndexesBlocking()
    {
        Set<Index> customIndexers = indexes.values().stream()
                                                    .filter(index -> !(index.getBackingTable().isPresent()))
                                                    .collect(Collectors.toSet());
        flushIndexesBlocking(customIndexers);
    }

    /**
     * @return all indexes which are marked as built and ready to use
     */
    public List<String> getBuiltIndexNames()
    {
        Set<String> allIndexNames = new HashSet<>();
        indexes.values().stream()
                .map(Index::getIndexName)
                .forEach(allIndexNames::add);
        return SystemKeyspace.getBuiltIndexes(baseCfs.keyspace.getName(), allIndexNames);
    }

    /**
     * @return all backing Tables used by registered indexes
     */
    public Set<ColumnFamilyStore> getAllIndexColumnFamilyStores()
    {
        Set<ColumnFamilyStore> backingTables = new HashSet<>();
        indexes.values().forEach(index -> index.getBackingTable().ifPresent(backingTables::add));
        return backingTables;
    }

    /**
     * @return if there are ANY indexes registered for this table
     */
    public boolean hasIndexes()
    {
        return !indexes.isEmpty();
    }

    /**
     * When building an index against existing data in sstables, add the given partition to the index
     */
    public void indexPartition(UnfilteredRowIterator partition, OpOrder.Group opGroup, Set<Index> indexes, int nowInSec)
    {
        if (!indexes.isEmpty())
        {
            DecoratedKey key = partition.partitionKey();
            Set<Index.Indexer> indexers = indexes.stream()
                                                 .map(index -> index.indexerFor(key,
                                                                                nowInSec,
                                                                                opGroup,
                                                                                IndexTransaction.Type.UPDATE))
                                                 .collect(Collectors.toSet());

            indexers.forEach(Index.Indexer::begin);

            try (RowIterator filtered = UnfilteredRowIterators.filter(partition, nowInSec))
            {
                if (!filtered.staticRow().isEmpty())
                    indexers.forEach(indexer -> indexer.insertRow(filtered.staticRow()));

                while (filtered.hasNext())
                {
                    Row row = filtered.next();
                    indexers.forEach(indexer -> indexer.insertRow(row));
                }
            }

            indexers.forEach(Index.Indexer::finish);
        }
    }

    /**
     * Delete all data from all indexes for this partition.
     * For when cleanup rips a partition out entirely.
     *
     * TODO : improve cleanup transaction to batch updates & perform them async
     */
    public void deletePartition(UnfilteredRowIterator partition, int nowInSec)
    {
        // we need to acquire memtable lock because secondary index deletion may
        // cause a race (see CASSANDRA-3712). This is done internally by the
        // index transaction when it commits
        CleanupTransaction indexTransaction = newCleanupTransaction(partition.partitionKey(),
                                                                    partition.columns(),
                                                                    nowInSec);
        indexTransaction.start();
        indexTransaction.onPartitionDeletion(partition.partitionLevelDeletion());
        indexTransaction.commit();

        while (partition.hasNext())
        {
            Unfiltered unfiltered = partition.next();
            if (unfiltered.kind() != Unfiltered.Kind.ROW)
                continue;

            indexTransaction = newCleanupTransaction(partition.partitionKey(),
                                                     partition.columns(),
                                                     nowInSec);
            indexTransaction.start();
            indexTransaction.onRowDelete((Row)unfiltered);
            indexTransaction.commit();
        }
    }

    /**
     * Called at query time to find the most selective of the registered index implementation
     * (i.e. the one likely to return the fewest results) from those registered.
     * Implementation specific validation of the target expression by the most selective
     * index should be performed in the searcherFor method to ensure that we pick the right
     * index regardless of the validity of the expression.
     *
     * This method is called at various points during the lifecycle of a ReadCommand (to obtain a Searcher,
     * get the index's underlying CFS for ReadOrderGroup, or an estimate of the result size from an average index
     * query).
     *
     * Ideally, we would do this relatively expensive operation only once, and attach the index to the
     * ReadCommand for future reference. This requires the index be passed onto additional commands generated
     * to process subranges etc.
     *
     * @param command ReadCommand to be executed
     * @return an Index instance, ready to use during execution of the command, or null if none
     * of the registered indexes can support the command.
     */
    public Index getBestIndexFor(ReadCommand command, boolean includeInTrace)
    {
        if (indexes.isEmpty() || command.rowFilter().isEmpty())
            return null;

        Set<Index> searchableIndexes = new HashSet<>();
        for (RowFilter.Expression expression : command.rowFilter())
        {
            indexes.values().stream()
                            .filter(index -> index.supportsExpression(expression.column(), expression.operator()))
                            .forEach(searchableIndexes::add);
        }

        if (searchableIndexes.isEmpty())
        {
            logger.debug("No applicable indexes found");
            if (includeInTrace)
                Tracing.trace("No applicable indexes found");
            return null;
        }

        Index selected = searchableIndexes.stream()
                                          .max((a, b) -> Longs.compare(a.getEstimatedResultRows(),
                                                                       b.getEstimatedResultRows()))
                                          .orElseThrow(() -> new AssertionError("Could not select most selective index"));

        // pay for an additional threadlocal get() rather than build the strings unnecessarily
        if (includeInTrace && Tracing.isTracing())
        {
            Tracing.trace("Index mean cardinalities are {}. Scanning with {}.",
                          searchableIndexes.stream().map(i -> i.getIndexName() + ':' + i.getEstimatedResultRows())
                                           .collect(Collectors.joining(",")),
                          selected.getIndexName());
        }
        return selected;
    }

    // convenience method which doesn't emit tracing messages
    public Index getBestIndexFor(ReadCommand command)
    {
        return getBestIndexFor(command, false);
    }

    /**
     * Called at write time to ensure that values present in the update
     * are valid according to the rules of all registered indexes which
     * will process it. The partition key as well as the clustering and
     * cell values for each row in the update may be checked by index
     * implementations
     * @param update PartitionUpdate containing the values to be validated by registered Index implementations
     * @throws InvalidRequestException
     */
    public void validate(PartitionUpdate update) throws InvalidRequestException
    {
        indexes.values()
               .stream()
               .filter(i -> i.indexes(update.columns()))
               .forEach(i -> i.validate(update));
    }

    /**
     * IndexRegistry methods
     */
    public void registerIndex(Index index)
    {
        indexes.put(index.getIndexMetadata().name, index);
        logger.debug("Registered index {}", index.getIndexMetadata().name);
    }

    public void unregisterIndex(Index index)
    {
        Index removed = indexes.remove(index.getIndexMetadata().name);
        logger.debug(removed == null ? "Index {} was not registered" : "Removed index {} from registry",
                     index.getIndexMetadata().name);
    }

    public Index getIndex(IndexMetadata metadata)
    {
        return indexes.get(metadata.name);
    }

    public Collection<Index> listIndexes()
    {
        return ImmutableSet.copyOf(indexes.values());
    }

    /**
     * Handling of index updates.
     * Implementations of the various IndexTransaction interfaces, for keeping indexes in sync with base data
     * during updates, compaction and cleanup. Plus factory methods for obtaining transaction instances.
     */

    /**
     * Transaction for updates on the write path.
     */
    public UpdateTransaction newUpdateTransaction(PartitionUpdate update, OpOrder.Group opGroup, int nowInSec)
    {
        if (!hasIndexes())
            return UpdateTransaction.NO_OP;

        // todo : optimize lookup, we can probably cache quite a bit of stuff, rather than doing
        // a linear scan every time. Holding off that though until CASSANDRA-7771 to figure out
        // exactly how indexes are to be identified & associated with a given partition update
        Index.Indexer[] indexers = indexes.values().stream()
                                          .filter(i -> i.indexes(update.columns()))
                                          .map(i -> i.indexerFor(update.partitionKey(),
                                                                 nowInSec,
                                                                 opGroup,
                                                                 IndexTransaction.Type.UPDATE))
                                          .toArray(Index.Indexer[]::new);

        return indexers.length == 0 ? UpdateTransaction.NO_OP : new WriteTimeTransaction(indexers);
    }

    /**
     * Transaction for use when merging rows during compaction
     */
    public CompactionTransaction newCompactionTransaction(DecoratedKey key,
                                                          PartitionColumns partitionColumns,
                                                          int versions,
                                                          int nowInSec)
    {
        // the check for whether there are any registered indexes is already done in CompactionIterator

        Index[] interestedIndexes = indexes.values().stream()
                                           .filter(i -> i.indexes(partitionColumns))
                                           .toArray(Index[]::new);

        return interestedIndexes.length == 0
               ? CompactionTransaction.NO_OP
               : new IndexGCTransaction(key, versions, nowInSec, interestedIndexes);
    }

    /**
     * Transaction for use when removing partitions during cleanup
     */
    public CleanupTransaction newCleanupTransaction(DecoratedKey key,
                                                    PartitionColumns partitionColumns,
                                                    int nowInSec)
    {
        //
        if (!hasIndexes())
            return CleanupTransaction.NO_OP;

        Index[] interestedIndexes = indexes.values().stream()
                                           .filter(i -> i.indexes(partitionColumns))
                                           .toArray(Index[]::new);

        return interestedIndexes.length == 0
               ? CleanupTransaction.NO_OP
               : new CleanupGCTransaction(key, nowInSec, interestedIndexes);
    }

    /**
     * A single use transaction for processing a partition update on the regular write path
     */
    private static final class WriteTimeTransaction implements UpdateTransaction
    {
        private final Index.Indexer[] indexers;

        private WriteTimeTransaction(Index.Indexer...indexers)
        {
            // don't allow null indexers, if we don't need any use a NullUpdater object
            for (Index.Indexer indexer : indexers) assert indexer != null;
            this.indexers = indexers;
        }

        public void start()
        {
            Arrays.stream(indexers).forEach(Index.Indexer::begin);
        }

        public void onPartitionDeletion(DeletionTime deletionTime)
        {
            Arrays.stream(indexers).forEach(h -> h.partitionDelete(deletionTime));
        }

        public void onRangeTombstone(RangeTombstone tombstone)
        {
            Arrays.stream(indexers) .forEach(h -> h.rangeTombstone(tombstone));
        }

        public void onInserted(Row row)
        {
            Arrays.stream(indexers).forEach(h -> h.insertRow(row));
        }

        public void onUpdated(Row existing, Row updated)
        {
            final Row.Builder toRemove = BTreeRow.sortedBuilder(existing.columns());
            toRemove.newRow(existing.clustering());
            final Row.Builder toInsert = BTreeRow.sortedBuilder(updated.columns());
            toInsert.newRow(updated.clustering());
            // diff listener collates the columns to be added & removed from the indexes
            RowDiffListener diffListener = new RowDiffListener()
            {
                public void onPrimaryKeyLivenessInfo(int i, Clustering clustering, LivenessInfo merged, LivenessInfo original)
                {
                    if (merged != null && merged != original)
                        toInsert.addPrimaryKeyLivenessInfo(merged);
                }

                public void onDeletion(int i, Clustering clustering, DeletionTime merged, DeletionTime original)
                {
                }

                public void onComplexDeletion(int i, Clustering clustering, ColumnDefinition column, DeletionTime merged, DeletionTime original)
                {
                }

                public void onCell(int i, Clustering clustering, Cell merged, Cell original)
                {
                    if (merged != null && merged != original)
                        toInsert.addCell(merged);

                    if (merged == null || (original != null && shouldCleanupOldValue(original, merged)))
                        toRemove.addCell(original);

                }
            };
            Rows.diff(diffListener, updated, updated.columns().mergeTo(existing.columns()), existing);
            Row oldRow = toRemove.build();
            Row newRow = toInsert.build();
            Arrays.stream(indexers).forEach(i -> i.updateRow(oldRow, newRow));
        }

        public void commit()
        {
            Arrays.stream(indexers).forEach(Index.Indexer::finish);
        }

        private boolean shouldCleanupOldValue(Cell oldCell, Cell newCell)
        {
            // If either the value or timestamp is different, then we
            // should delete from the index. If not, then we can infer that
            // at least one of the cells is an ExpiringColumn and that the
            // difference is in the expiry time. In this case, we don't want to
            // delete the old value from the index as the tombstone we insert
            // will just hide the inserted value.
            // Completely identical cells (including expiring columns with
            // identical ttl & localExpirationTime) will not get this far due
            // to the oldCell.equals(newCell) in StandardUpdater.update
            return !oldCell.value().equals(newCell.value()) || oldCell.timestamp() != newCell.timestamp();
        }
    }

    /**
     * A single-use transaction for updating indexes for a single partition during compaction where the only
     * operation is to merge rows
     * TODO : make this smarter at batching updates so we can use a single transaction to process multiple rows in
     * a single partition
     */
    private static final class IndexGCTransaction implements CompactionTransaction
    {
        private final DecoratedKey key;
        private final int versions;
        private final int nowInSec;
        private final Index[] indexes;

        private Row[] rows;

        private IndexGCTransaction(DecoratedKey key,
                                   int versions,
                                   int nowInSec,
                                   Index...indexes)
        {
            // don't allow null indexers, if we don't have any, use a noop transaction
            for (Index index : indexes) assert index != null;

            this.key = key;
            this.versions = versions;
            this.indexes = indexes;
            this.nowInSec = nowInSec;
        }

        public void start()
        {
            if (versions > 0)
                rows = new Row[versions];
        }

        public void onRowMerge(Columns columns, Row merged, Row...versions)
        {
            // Diff listener constructs rows representing deltas between the merged and original versions
            // These delta rows are then passed to registered indexes for removal processing
            final Row.Builder[] builders = new Row.Builder[versions.length];
            RowDiffListener diffListener = new RowDiffListener()
            {
                public void onPrimaryKeyLivenessInfo(int i, Clustering clustering, LivenessInfo merged, LivenessInfo original)
                {
                }

                public void onDeletion(int i, Clustering clustering, DeletionTime merged, DeletionTime original)
                {
                }

                public void onComplexDeletion(int i, Clustering clustering, ColumnDefinition column, DeletionTime merged, DeletionTime original)
                {
                }

                public void onCell(int i, Clustering clustering, Cell merged, Cell original)
                {
                    if (original != null && merged == null)
                    {
                        if (builders[i] == null)
                        {
                            builders[i] = BTreeRow.sortedBuilder(columns);
                            builders[i].newRow(clustering);
                        }
                        builders[i].addCell(original);
                    }
                }
            };

            Rows.diff(diffListener, merged, columns, versions);

            for(int i = 0; i < builders.length; i++)
                if (builders[i] != null)
                    rows[i] = builders[i].build();
        }

        public void commit()
        {
            if (rows == null)
                return;

            try (OpOrder.Group opGroup = Keyspace.writeOrder.start())
            {
                Index.Indexer[] indexers = Arrays.stream(indexes)
                                                 .map(i -> i.indexerFor(key, nowInSec, opGroup, Type.COMPACTION))
                                                 .toArray(Index.Indexer[]::new);

                Arrays.stream(indexers).forEach(Index.Indexer::begin);

                for (Row row : rows)
                    if (row != null)
                        Arrays.stream(indexers).forEach(indexer -> indexer.removeRow(row));

                Arrays.stream(indexers).forEach(Index.Indexer::finish);
            }
        }
    }

    /**
     * A single-use transaction for updating indexes for a single partition during cleanup, where
     * partitions and rows are only removed
     * TODO : make this smarter at batching updates so we can use a single transaction to process multiple rows in
     * a single partition
     */
    private static final class CleanupGCTransaction implements CleanupTransaction
    {
        private final DecoratedKey key;
        private final int nowInSec;
        private final Index[] indexes;

        private Row row;
        private DeletionTime partitionDelete;

        private CleanupGCTransaction(DecoratedKey key,
                                     int nowInSec,
                                     Index...indexes)
        {
            // don't allow null indexers, if we don't have any, use a noop transaction
            for (Index index : indexes) assert index != null;

            this.key = key;
            this.indexes = indexes;
            this.nowInSec = nowInSec;
        }

        public void start()
        {
        }

        public void onPartitionDeletion(DeletionTime deletionTime)
        {
            partitionDelete = deletionTime;
        }

        public void onRowDelete(Row row)
        {
            this.row = row;
        }

        public void commit()
        {
            if (row == null && partitionDelete == null)
                return;

            try (OpOrder.Group opGroup = Keyspace.writeOrder.start())
            {
                Index.Indexer[] indexers = Arrays.stream(indexes)
                                                 .map(i -> i.indexerFor(key, nowInSec, opGroup, Type.CLEANUP))
                                                 .toArray(Index.Indexer[]::new);

                Arrays.stream(indexers).forEach(Index.Indexer::begin);

                if (partitionDelete != null)
                    Arrays.stream(indexers).forEach(indexer -> indexer.partitionDelete(partitionDelete));

                if (row != null)
                    Arrays.stream(indexers).forEach(indexer -> indexer.removeRow(row));

                Arrays.stream(indexers).forEach(Index.Indexer::finish);
            }
        }
    }

    private static void executeBlocking(Callable<?> task)
    {
        if (null != task)
            FBUtilities.waitOnFuture(blockingExecutor.submit(task));
    }

    private static void executeAllBlocking(Stream<Index> indexers, Function<Index, Callable<?>> function)
    {
        List<Future<?>> waitFor = new ArrayList<>();
        indexers.forEach(indexer -> {
            Callable<?> task = function.apply(indexer);
            if (null != task)
                waitFor.add(blockingExecutor.submit(task));
        });
        FBUtilities.waitOnFutures(waitFor);
    }
}
