package com.netflix.astyanax.recipes.locks;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.astyanax.ColumnListMutation;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.model.ConsistencyLevel;
import com.netflix.astyanax.util.RangeBuilder;
import com.netflix.astyanax.util.TimeUUIDUtils;

/**
 * Takes a distributed row lock for a single row.
 * 
 * Algorithm 1. Write a column with name <prefix>_<uuid>. Value is a TTL. 2.
 * Read back all columns with <prefix> a) count==1 Got the lock b) otherwise
 * Look for stale locks a) count==1 Got the lock b) otherwise No lock
 * 
 * @author elandau
 * 
 * @param <K>
 */
public class ColumnPrefixDistributedRowLock<K> implements DistributedRowLock {
    public static final int LOCK_TIMEOUT = 60;
    public static final TimeUnit DEFAULT_OPERATION_TIMEOUT_UNITS = TimeUnit.MINUTES;
    public static final String DEFAULT_LOCK_PREFIX = "_LOCK_";

    private final ColumnFamily<K, String> columnFamily; // The column family for
                                                        // data and lock
    private final Keyspace keyspace; // The keyspace
    private final K key; // Key being locked

    private long timeout = LOCK_TIMEOUT;
    private TimeUnit timeoutUnits = DEFAULT_OPERATION_TIMEOUT_UNITS;
    private String prefix = DEFAULT_LOCK_PREFIX; // Prefix to identify the lock
                                                 // columns
    private ConsistencyLevel consistencyLevel = ConsistencyLevel.CL_QUORUM;
    private boolean failOnStaleLock = false;
    private String lockColumn = null;
    private List<String> locksToDelete = Lists.newArrayList();
    private Integer ttl;

    public ColumnPrefixDistributedRowLock(Keyspace keyspace, ColumnFamily<K, String> columnFamily, K key) {
        this.keyspace = keyspace;
        this.columnFamily = columnFamily;
        this.key = key;
        this.lockColumn = prefix + TimeUUIDUtils.getUniqueTimeUUIDinMicros();
    }

    /**
     * Modify the consistency level being used. Consistency should always be a
     * variant of quorum. The default is CL_QUORUM, which is OK for single
     * region. For multi region the consistency level should be CL_LOCAL_QUORUM.
     * CL_EACH_QUORUM can be used but will Incur substantial latency.
     * 
     * @param consistencyLevel
     * @return
     */
    public ColumnPrefixDistributedRowLock<K> withConsistencyLevel(ConsistencyLevel consistencyLevel) {
        this.consistencyLevel = consistencyLevel;
        return this;
    }

    /**
     * Specify the prefix that uniquely distinguishes the lock columns from data
     * column
     * 
     * @param prefix
     * @return
     */
    public ColumnPrefixDistributedRowLock<K> withColumnPrefix(String prefix) {
        this.prefix = prefix;
        this.lockColumn = prefix + TimeUUIDUtils.getUniqueTimeUUIDinMicros();
        return this;
    }

    /**
     * Override the autogenerated lock column.
     * 
     * @param column
     * @return
     */
    public ColumnPrefixDistributedRowLock<K> withLockColumn(String column) {
        this.lockColumn = column;
        return this;
    }

    /**
     * When set to true the operation will fail if a stale lock is detected
     * 
     * @param failOnStaleLock
     * @return
     */
    public ColumnPrefixDistributedRowLock<K> failOnStaleLock(boolean failOnStaleLock) {
        this.failOnStaleLock = failOnStaleLock;
        return this;
    }

    /**
     * Time for failed locks. Under normal circumstances the lock column will be
     * deleted. If not then this lock column will remain and the row will remain
     * locked. The lock will expire after this timeout.
     * 
     * @param timeout
     * @param unit
     * @return
     */
    public ColumnPrefixDistributedRowLock<K> expireLockAfter(long timeout, TimeUnit unit) {
        this.timeout = timeout;
        this.timeoutUnits = unit;
        return this;
    }

    public ColumnPrefixDistributedRowLock<K> withTtl(Integer ttl) {
        this.ttl = ttl;
        return this;
    }

    /**
     * Write a lock column using the current time
     * 
     * @return
     * @throws Exception
     */
    @Override
    public void acquire() throws Exception {
        try {
            long curTimeMicros = getCurrentTimeMicros();
            lockColumn = writeLockColumn(curTimeMicros);

            verifyLock(curTimeMicros);
        }
        catch (Exception e) {
            release();
            throw e;
        }
    }

    /**
     * Verify that the lock was acquired
     * 
     * @param curTimeMicros
     * @throws Exception
     */
    public void verifyLock(long curTimeMicros) throws Exception, BusyLockException, StaleLockException {
        // Phase 2: Read back all columns. There should be only 1 if we got the
        // lock
        // TODO: May want to support reading entire row
        Map<String, Long> lockResult = readLockColumns();

        // Cleanup and check that we really got the lock
        for (Entry<String, Long> entry : lockResult.entrySet()) {
            // This is a stale lock that was never cleaned up
            if (entry.getValue() != 0 && curTimeMicros > entry.getValue()) {
                if (this.failOnStaleLock) {
                    throw new StaleLockException("Stale lock on row " + key + ".  Manual cleanup requried.");
                }
                locksToDelete.add(entry.getKey());
            }
            // Lock already taken, and not by us
            else if (!entry.getKey().equals(lockColumn)) {
                throw new BusyLockException("Lock already acquired for " + entry.getKey());
            }
        }
    }

    /**
     * Release the lock by releasing this and any other stale lock columns
     */
    @Override
    public void release() throws Exception {
        if (!locksToDelete.isEmpty()) {
            MutationBatch m = keyspace.prepareMutationBatch().setConsistencyLevel(consistencyLevel);
            fillReleaseMutation(m);
            m.execute();
        }
    }

    /**
     * Fill a mutation that will release the locks. This may be used from a
     * separate recipe to release multiple locks.
     * 
     * @param m
     */
    public void fillReleaseMutation(MutationBatch m) {
        // Add the deletes to the end of the mutation
        ColumnListMutation<String> row = m.withRow(columnFamily, key);
        for (String c : locksToDelete) {
            row.deleteColumn(c);
        }
        locksToDelete.clear();
    }

    /**
     * Return a mapping of existing lock columns and their expiration time
     * 
     * @return
     * @throws Exception
     */
    public Map<String, Long> readLockColumns() throws Exception {
        ColumnList<String> lockResult = keyspace.prepareQuery(columnFamily).setConsistencyLevel(consistencyLevel)
                .getKey(key)
                .withColumnRange(new RangeBuilder().setStart(prefix + "\u0000").setEnd(prefix + "\uFFFF").build())
                .execute().getResult();

        Map<String, Long> result = Maps.newLinkedHashMap();
        for (Column<String> c : lockResult) {
            result.put(c.getName(), c.getLongValue());
        }

        return result;
    }

    /**
     * Release all locks. Use this carefully as it could release a lock for a
     * running operation
     * 
     * @return
     * @throws Exception
     */
    public Map<String, Long> releaseAllLocks() throws Exception {
        return releaseLocks(true);
    }

    /**
     * Release all expired locks for this key.
     * 
     * @return
     * @throws Exception
     */
    public Map<String, Long> releaseExpiredLocks() throws Exception {
        return releaseLocks(false);
    }

    /**
     * Delete locks columns. Set force=true to remove locks that haven't been
     * expired yet.
     * 
     * @param force
     * @return
     * @throws Exception
     */
    public Map<String, Long> releaseLocks(boolean force) throws Exception {
        Map<String, Long> locksToDelete = readLockColumns();
        release();

        MutationBatch m = keyspace.prepareMutationBatch().setConsistencyLevel(consistencyLevel);
        ColumnListMutation<String> row = m.withRow(columnFamily, key);
        long now = getCurrentTimeMicros();
        for (Entry<String, Long> c : locksToDelete.entrySet()) {
            if (force || (c.getValue() > 0 && c.getValue() < now)) {
                row.deleteColumn(c.getKey());
            }
        }
        m.execute();

        return locksToDelete;
    }

    /**
     * Get the current system time
     * 
     * @return
     */
    private long getCurrentTimeMicros() {
        return TimeUnit.MICROSECONDS.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Write a lock for the specified time.
     * 
     * @param time
     * @return The column name for the lock
     * @throws Exception
     */
    protected String writeLockColumn(long time) throws Exception {
        locksToDelete.add(lockColumn);

        MutationBatch m = keyspace.prepareMutationBatch().setConsistencyLevel(consistencyLevel);
        fillLockMutation(m, time, this.ttl);
        m.execute();

        return lockColumn;
    }

    /**
     * Fill a mutation with the lock column. This may be used when the mutation
     * is executed externally but should be used with extreme caution to ensure
     * the lock is properly release
     * 
     * @param m
     * @param time
     * @param ttl
     */
    public void fillLockMutation(MutationBatch m, Long time, Integer ttl) {
        locksToDelete.add(lockColumn);
        if (time != null) {
            m.withRow(columnFamily, key).putColumn(lockColumn,
                    time + TimeUnit.MICROSECONDS.convert(timeout, timeoutUnits), ttl);
        }
        else {
            m.withRow(columnFamily, key).putColumn(lockColumn, 0, ttl);
        }
    }

    public Keyspace getKeyspace() {
        return keyspace;
    }

    public ConsistencyLevel getConsistencyLevel() {
        return consistencyLevel;
    }

    public String getLockColumn() {
        return this.lockColumn;
    }

}
