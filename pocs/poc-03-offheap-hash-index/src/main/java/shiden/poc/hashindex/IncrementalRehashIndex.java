package shiden.poc.hashindex;

import java.lang.foreign.ValueLayout;

/**
 * Dual-Table Incremental Rehashing Index Manager ($O(1)$ Migration Completion).
 * <p>
 * Eliminates Stop-The-World (STW) hash table resize latency spikes:
 * <ul>
 *   <li>Maintains two active tables: <code>T0</code> (current) and <code>T1</code> (resizing target).</li>
 *   <li>Automatically triggers $2\times$ map expansion when <code>T0</code> load factor exceeds 85%.</li>
 *   <li>Levies a 1-cluster incremental migration tax on every <code>PUT</code>, <code>GET</code>, or <code>DELETE</code>.</li>
 *   <li>Uses an explicit $O(1)$ primitive counter (<code>t0RemainingEntries</code>) to finalize migration without full table scans.</li>
 *   <li>All new <code>PUT</code> operations write directly to <code>T1</code> during rehashing.</li>
 *   <li><code>GET</code> operations route to <code>T1</code> first, falling back to <code>T0</code> if absent.</li>
 * </ul>
 * </p>
 */
public class IncrementalRehashIndex implements AutoCloseable {

    public static final double DEFAULT_REHASH_THRESHOLD = 0.85;

    private OffHeapRobinHoodHashIndex t0;
    private OffHeapRobinHoodHashIndex t1;
    private final double rehashThreshold;

    private int rehashCursor = 0;
    private int t0RemainingEntries = 0;

    /**
     * Creates an IncrementalRehashIndex with initial capacity.
     */
    public IncrementalRehashIndex(int initialCapacity) {
        this(initialCapacity, DEFAULT_REHASH_THRESHOLD);
    }

    public IncrementalRehashIndex(int initialCapacity, double rehashThreshold) {
        this.t0 = new OffHeapRobinHoodHashIndex(initialCapacity);
        this.t1 = null;
        this.rehashThreshold = rehashThreshold;
    }

    /**
     * Inserts or updates a key mapping.
     */
    public boolean put(long key, int pageId, short slotId) {
        // If rehashing, write directly to T1
        if (t1 != null) {
            boolean success = t1.put(key, pageId, slotId);
            stepRehash();
            return success;
        }

        // Otherwise write to T0
        boolean success = t0.put(key, pageId, slotId);

        // Check if load factor trigger surpassed
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
            if (result != -1L) {
                stepRehash();
                return result;
            }

            result = t0.get(key);
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
        this.t1 = new OffHeapRobinHoodHashIndex(newCapacity);
        this.rehashCursor = 0;
        this.t0RemainingEntries = t0.size();
    }

    /**
     * Migrates occupied bucket clusters from T0 to T1 with Adaptive Dynamic Tax Pacing.
     */
    private void stepRehash() {
        if (t1 == null) {
            return;
        }

        if (t0RemainingEntries <= 0) {
            finalizeRehash();
            return;
        }

        // Adaptive Pacing: scale migration tax during heavy write bursts
        int t1AvailableSpace = Math.max(1, t1.capacity() - t1.size());
        int tax = Math.max(1, (int) Math.ceil((double) t0RemainingEntries / t1AvailableSpace));

        int capacity = t0.capacity();

        for (int stepTax = 0; stepTax < tax; stepTax++) {
            boolean migrated = false;
            for (int i = 0; i < capacity; i++) {
                int targetIdx = (rehashCursor + i) & (capacity - 1); // Power of 2 fast bitmasking
                long bOffset = (long) targetIdx << OffHeapRobinHoodHashIndex.BUCKET_SHIFT;

                long word1 = t0.segment().get(ValueLayout.JAVA_LONG, bOffset + OffHeapRobinHoodHashIndex.WORD1_OFFSET);
                short distance = (short) word1;

                if (distance != OffHeapRobinHoodHashIndex.EMPTY_DISTANCE) {
                    long word0 = t0.segment().get(ValueLayout.JAVA_LONG, bOffset + OffHeapRobinHoodHashIndex.WORD0_OFFSET);

                    short slotId = (short) (word0 >>> 32);
                    int pageId = (int) word0;

                    long keySeed = (((long) pageId) << 32) | (slotId & 0xFFFFL);
                    t1.put(keySeed, pageId, slotId);

                    // Mark bucket in T0 as empty
                    t0.segment().set(ValueLayout.JAVA_SHORT, bOffset + OffHeapRobinHoodHashIndex.DISTANCE_OFFSET,
                            OffHeapRobinHoodHashIndex.EMPTY_DISTANCE);

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
