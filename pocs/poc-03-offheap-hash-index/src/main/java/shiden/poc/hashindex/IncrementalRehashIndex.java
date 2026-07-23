package shiden.poc.hashindex;

import sun.misc.Unsafe;
import java.lang.reflect.Field;

/**
 * Multi-Engine Dual-Table Incremental Rehashing Index Manager ($O(1)$ Migration Completion).
 * <p>
 * Eliminates Stop-The-World (STW) hash table resize latency spikes:
 * <ul>
 *   <li>Supports configurable index engine backends ({@link IndexEngineType#SAFE_FFM_MONOLITHIC},
 *       {@link IndexEngineType#UNSAFE_FASTPATH_MONOLITHIC}, or {@link IndexEngineType#UNSAFE_CONTIGUOUS_SHARDED}).</li>
 *   <li>Enforces a minimum capacity of 65,536 buckets (1.0 MB), ensuring 100% exact 64-bit hash reconstruction.</li>
 *   <li>Automatically triggers $2\times$ map expansion when <code>T0</code> load factor exceeds 85%.</li>
 *   <li>Levies a dynamic cluster migration tax on every <code>PUT</code>, <code>GET</code>, or <code>DELETE</code>.</li>
 *   <li>1-Cycle Direct Address Migration: <code>stepRehash()</code> dereferences native memory via <code>UNSAFE.getLong(rawAddress + bOffset)</code>
 *       to eliminate FFM bounds/scope overhead during migration steps.</li>
 * </ul>
 * </p>
 */
public class IncrementalRehashIndex implements AutoCloseable {

    private static final Unsafe UNSAFE;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access sun.misc.Unsafe", e);
        }
    }

    public static final double DEFAULT_REHASH_THRESHOLD = 0.85;
    public static final int MIN_REHASH_CAPACITY = 65_536; // 2^16 (1.0 MB)
    public static final int DEFAULT_SHARDS = 32;

    private final IndexEngineType engineType;
    private final double rehashThreshold;

    private ShidenHashIndex t0;
    private ShidenHashIndex t1;

    private int rehashCursor = 0;
    private int t0RemainingEntries = 0;

    /**
     * Creates an IncrementalRehashIndex defaulting to UNSAFE_FASTPATH_MONOLITHIC engine.
     */
    public IncrementalRehashIndex(int initialCapacity) {
        this(initialCapacity, IndexEngineType.UNSAFE_FASTPATH_MONOLITHIC, DEFAULT_REHASH_THRESHOLD);
    }

    /**
     * Creates an IncrementalRehashIndex with specified load factor threshold defaulting to UNSAFE_FASTPATH_MONOLITHIC engine.
     */
    public IncrementalRehashIndex(int initialCapacity, double rehashThreshold) {
        this(initialCapacity, IndexEngineType.UNSAFE_FASTPATH_MONOLITHIC, rehashThreshold);
    }

    /**
     * Creates an IncrementalRehashIndex with specified engine type and default load factor threshold.
     */
    public IncrementalRehashIndex(int initialCapacity, IndexEngineType engineType) {
        this(initialCapacity, engineType, DEFAULT_REHASH_THRESHOLD);
    }

    /**
     * Creates an IncrementalRehashIndex with specified engine type and load factor threshold.
     */
    public IncrementalRehashIndex(int initialCapacity, IndexEngineType engineType, double rehashThreshold) {
        this.engineType = engineType;
        this.rehashThreshold = rehashThreshold;

        int cap = Math.max(MIN_REHASH_CAPACITY, initialCapacity);
        if ((cap & (cap - 1)) != 0) {
            cap = Integer.highestOneBit(cap) << 1;
        }

        this.t0 = createEngine(engineType, cap);
        this.t1 = null;
    }

    private static ShidenHashIndex createEngine(IndexEngineType engineType, int capacity) {
        return switch (engineType) {
            case SAFE_FFM_MONOLITHIC -> new OffHeapRobinHoodHashIndex(capacity);
            case UNSAFE_FASTPATH_MONOLITHIC -> new UnsafeFastPathRobinHoodHashIndex(capacity);
            case UNSAFE_CONTIGUOUS_SHARDED -> new ContiguousShardedOffHeapHashIndex(capacity, DEFAULT_SHARDS);
        };
    }

    /**
     * Inserts or updates a key mapping.
     */
    public boolean put(long key, int pageId, short slotId) {
        if (t1 != null) {
            boolean success = t1.put(key, pageId, slotId);
            stepRehash();
            if (t1 != null && t1.loadFactor() >= rehashThreshold) {
                while (t1 != null && t0RemainingEntries > 0) {
                    stepRehash();
                }
            }
            return success;
        }

        boolean success = t0.put(key, pageId, slotId);

        if (t0.loadFactor() >= rehashThreshold && t1 == null) {
            startRehash();
        }

        return success;
    }

    /**
     * Look up record pointer by key.
     */
    public long get(long key) {
        if (t1 != null) {
            long result = t1.get(key);
            if (result == -1L) {
                result = t0.get(key);
            }
            stepRehash();
            return result;
        }

        return t0.get(key);
    }

    /**
     * Deletes record entry by key.
     */
    public boolean delete(long key) {
        if (t1 != null) {
            boolean deleted = t1.delete(key);
            if (!deleted) {
                deleted = t0.delete(key);
                if (deleted) {
                    t0RemainingEntries--;
                }
            }
            stepRehash();
            return deleted;
        }

        return t0.delete(key);
    }

    /**
     * Initiates $2\times$ table capacity expansion.
     */
    private void startRehash() {
        int newCapacity = t0.capacity() * 2;
        this.t1 = createEngine(engineType, newCapacity);
        this.rehashCursor = 0;
        this.t0RemainingEntries = t0.size();
    }

    /**
     * Migrates occupied bucket clusters from T0 to T1 via 1-cycle Unsafe native address dereferencing.
     */
    private void stepRehash() {
        if (t1 == null) {
            return;
        }

        if (t0RemainingEntries <= 0) {
            finalizeRehash();
            return;
        }

        int t1AvailableSpace = Math.max(1, t1.capacity() - t1.size());
        int tax = Math.max(1, (int) Math.ceil((double) t0RemainingEntries / t1AvailableSpace));

        int capacity = t0.capacity();
        long t0Address = t0.rawAddress();

        for (int stepTax = 0; stepTax < tax; stepTax++) {
            boolean migrated = false;
            for (int i = 0; i < capacity; i++) {
                int targetIdx = (rehashCursor + i) & (capacity - 1);
                long bOffset = (long) targetIdx << OffHeapRobinHoodHashIndex.BUCKET_SHIFT;
                long bAddress = t0Address + bOffset;

                // 1-Cycle Unsafe Direct Address Read
                long word1 = UNSAFE.getLong(bAddress + OffHeapRobinHoodHashIndex.WORD1_OFFSET);
                short distance = (short) word1;

                if (distance != OffHeapRobinHoodHashIndex.EMPTY_DISTANCE) {
                    long word0 = UNSAFE.getLong(bAddress + OffHeapRobinHoodHashIndex.WORD0_OFFSET);
                    short fingerprint = (short) (word0 >>> 48);
                    short slotId = (short) (word0 >>> 32);
                    int pageId = (int) word0;
                    int hashUpper = (int) (word1 >>> 32);

                    // Reconstruct 100% exact 64-bit hash via engine implementation
                    long originalHash = t0.reconstructHash(targetIdx, distance, fingerprint, hashUpper);

                    t1.putByHash(originalHash, pageId, slotId);

                    rehashCursor = (targetIdx + 1) & (capacity - 1);
                    t0RemainingEntries--;
                    migrated = true;

                    if (t0RemainingEntries <= 0) {
                        finalizeRehash();
                        return;
                    }
                    break;
                }
            }

            if (!migrated) {
                finalizeRehash();
                return;
            }
        }
    }

    private void finalizeRehash() {
        if (t0 != null) {
            t0.close();
        }
        t0 = t1;
        t1 = null;
        rehashCursor = 0;
        t0RemainingEntries = 0;
    }

    public IndexEngineType engineType() {
        return engineType;
    }

    public boolean isRehashing() {
        return t1 != null;
    }

    public int totalCapacity() {
        return t0.capacity() + (t1 != null ? t1.capacity() : 0);
    }

    public int activeCapacity() {
        return t0.capacity();
    }

    public int size() {
        return t0.size() + (t1 != null ? t1.size() : 0);
    }

    @Override
    public void close() {
        if (t0 != null) {
            t0.close();
        }
        if (t1 != null) {
            t1.close();
        }
    }
}
