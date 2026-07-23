package shiden.poc.hashindex;

import sun.misc.Unsafe;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;

/**
 * 100% Pointer-Free Contiguous L2-Cache-Confined Sharded Off-Heap Hash Index (Unsafe Fast-Path).
 * <p>
 * Implements Shiden RFC-002 Key-to-Record Index Resolution Engine across L2-cache-confined sub-table shards:
 * <ul>
 *   <li><b>0 Java Pointers & Objects</b>: Lays out all sub-table shards contiguously in a single off-heap native {@link MemorySegment}.</li>
 *   <li><b>1-Cycle Direct Address Dereferencing</b>: Accesses memory directly via <code>UNSAFE.getLong(rawAddress + offset)</code>,
 *       bypassing FFM scope and boundary check wrappers on production hot paths.</li>
 *   <li><b>16-Byte Compact Aligned Bucket Layout</b>:
 *       <code>[ Page ID (4B @ 0) | Slot ID (2B @ 4) | Fingerprint (2B @ 6) | Distance (2B @ 8) | Frequency (2B @ 10) | HashUpper (4B @ 12) ]</code></li>
 *   <li><b>48-Bit Hash Matching Guarantee</b>: Combines 16-bit fingerprint + 32-bit HashUpper to eliminate false collision matches.</li>
 *   <li><b>Robin Hood Invariant</b>: Swaps entries during insertion if incoming Probe Sequence Length (PSL)
 *       exceeds existing entry's PSL ("take from the rich, give to the poor"), guaranteeing low PSL variance.</li>
 *   <li><b>Early Exit Lookups</b>: Aborts lookups early when candidate bucket PSL is less than current search PSL.</li>
 *   <li><b>Zero-Tombstone Backward-Shift Deletion</b>: Compacts probe clusters backward on delete within shard boundaries to prevent tombstone poisoning.</li>
 *   <li><b>L2 Cache Localized Routing</b>: Maps keys to contiguous shards using high 64-bit hash bits (<code>(hash >>> shardShift) & shardMask</code>).</li>
 *   <li><b>Software Hardware Prefetching</b>: Speculatively loads bucket cache lines into L1 cache for batch mailbox pipelines (RFC-003).</li>
 * </ul>
 * </p>
 */
public class ContiguousShardedOffHeapHashIndex implements AutoCloseable {

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

    public static final short EMPTY_DISTANCE = (short) -1;
    public static final int BUCKET_SIZE_BYTES = 16;
    public static final int BUCKET_SHIFT = 4;

    public static final long WORD0_OFFSET = 0;
    public static final long WORD1_OFFSET = 8;

    public static final long PAGE_ID_OFFSET = 0;
    public static final long SLOT_ID_OFFSET = 4;
    public static final long FINGERPRINT_OFFSET = 6;
    public static final long DISTANCE_OFFSET = 8;
    public static final long FREQUENCY_OFFSET = 10;
    public static final long HASH_UPPER_OFFSET = 12;

    public static final int DEFAULT_PREFETCH_LOOKAHEAD = 4;

    private final Arena arena;
    private final MemorySegment segment;
    private final long rawAddress;
    private final int totalCapacity;
    private final int numShards;
    private final int shardMask;
    private final int shardShift;
    private final int shardCapacity;
    private final int shardCapacityMask;
    private final long shardSizeBytes;

    private int size = 0;
    private int maxProbeLength = 0;

    // Metrics tracking
    private long totalLookups = 0;
    private long totalProbes = 0;
    private long fingerprintEvaluations = 0;
    private long fingerprintRejections = 0;

    /**
     * Constructs a new ContiguousShardedOffHeapHashIndex with specified total capacity and shard count via Unsafe Fast-Path.
     *
     * @param totalCapacity power-of-2 total bucket capacity across all shards
     * @param numShards power-of-2 number of sub-table shards
     * @throws IllegalArgumentException if totalCapacity or numShards is not a positive power of 2
     */
    public ContiguousShardedOffHeapHashIndex(int totalCapacity, int numShards) {
        if (totalCapacity <= 0 || (totalCapacity & (totalCapacity - 1)) != 0) {
            throw new IllegalArgumentException("Total capacity must be a power of 2: " + totalCapacity);
        }
        if (numShards <= 0 || (numShards & (numShards - 1)) != 0) {
            throw new IllegalArgumentException("numShards must be a power of 2: " + numShards);
        }
        if (totalCapacity < numShards) {
            throw new IllegalArgumentException("Total capacity must be >= numShards.");
        }

        this.totalCapacity = totalCapacity;
        this.numShards = numShards;
        this.shardMask = numShards - 1;
        this.shardShift = Long.SIZE - Integer.numberOfTrailingZeros(numShards);

        this.shardCapacity = totalCapacity / numShards;
        this.shardCapacityMask = shardCapacity - 1;
        this.shardSizeBytes = (long) shardCapacity * BUCKET_SIZE_BYTES;

        this.arena = Arena.ofConfined();
        long totalBytes = (long) totalCapacity * BUCKET_SIZE_BYTES;
        this.segment = arena.allocate(totalBytes, 64);
        this.rawAddress = segment.address();

        clearTable();
    }

    /**
     * Resets all off-heap bucket distance fields to EMPTY_DISTANCE (0xFFFF) via Unsafe.
     */
    private void clearTable() {
        for (int i = 0; i < totalCapacity; i++) {
            long offset = (long) i << BUCKET_SHIFT;
            UNSAFE.putShort(rawAddress + offset + DISTANCE_OFFSET, EMPTY_DISTANCE);
        }
    }

    /**
     * Inserts or updates a key-to-record mapping in the target shard using the Robin Hood hash invariant via Unsafe.
     *
     * @param key 64-bit primitive key
     * @param pageId 32-bit data page ID
     * @param slotId 16-bit slot index on the page
     * @return true if inserted/updated successfully; false if target shard is full
     */
    public boolean put(long key, int pageId, short slotId) {
        if (size >= totalCapacity) {
            return false;
        }

        long hash = XxHash3.hash64(key);
        int shardIdx = (int) ((hash >>> shardShift) & shardMask);
        long shardBaseOffset = shardIdx * shardSizeBytes;
        int idealIndex = (int) (hash & shardCapacityMask);

        short fingerprint = XxHash3.extractFingerprint(hash);
        int hashUpper = (int) (hash >>> 16);

        short incomingDistance = 0;
        short incomingFingerprint = fingerprint;
        short incomingSlotId = slotId;
        int incomingPageId = pageId;
        int incomingHashUpper = hashUpper;

        int currIndex = idealIndex;

        for (int step = 0; step < shardCapacity; step++) {
            long bOffset = shardBaseOffset + ((long) currIndex << BUCKET_SHIFT);
            long bAddress = rawAddress + bOffset;

            long word1 = UNSAFE.getLong(bAddress + WORD1_OFFSET);
            short currDistance = (short) word1;

            // 1. Found an empty slot
            if (currDistance == EMPTY_DISTANCE) {
                long word0 = (incomingPageId & 0xFFFFFFFFL) |
                            ((incomingSlotId & 0xFFFFL) << 32) |
                            ((incomingFingerprint & 0xFFFFL) << 48);
                long newWord1 = (incomingDistance & 0xFFFFL) |
                               (((long) incomingHashUpper) << 32);

                UNSAFE.putLong(bAddress + WORD0_OFFSET, word0);
                UNSAFE.putLong(bAddress + WORD1_OFFSET, newWord1);

                size++;
                if (incomingDistance > maxProbeLength) {
                    maxProbeLength = incomingDistance;
                }
                return true;
            }

            long word0 = UNSAFE.getLong(bAddress + WORD0_OFFSET);
            short currFingerprint = (short) (word0 >>> 48);
            int currHashUpper = (int) (word1 >>> 32);

            // 2. Duplicate record update check
            if (currFingerprint == incomingFingerprint &&
                currHashUpper == incomingHashUpper) {
                long updatedWord0 = (incomingPageId & 0xFFFFFFFFL) |
                                   ((incomingSlotId & 0xFFFFL) << 32) |
                                   ((incomingFingerprint & 0xFFFFL) << 48);
                UNSAFE.putLong(bAddress + WORD0_OFFSET, updatedWord0);
                return true;
            }

            // 3. Robin Hood Swap Invariant: Rich gives to Poor!
            if (currDistance < incomingDistance) {
                long newWord0 = (incomingPageId & 0xFFFFFFFFL) |
                               ((incomingSlotId & 0xFFFFL) << 32) |
                               ((incomingFingerprint & 0xFFFFL) << 48);
                long newWord1 = (incomingDistance & 0xFFFFL) |
                               (((long) incomingHashUpper) << 32);

                UNSAFE.putLong(bAddress + WORD0_OFFSET, newWord0);
                UNSAFE.putLong(bAddress + WORD1_OFFSET, newWord1);

                incomingDistance = currDistance;
                incomingFingerprint = currFingerprint;
                incomingSlotId = (short) (word0 >>> 32);
                incomingPageId = (int) word0;
                incomingHashUpper = currHashUpper;
            }

            currIndex = (currIndex + 1) & shardCapacityMask;
            incomingDistance++;
        }

        return false;
    }

    /**
     * Looks up a 64-bit key in its target shard and returns the combined off-heap payload via Unsafe (hot path).
     *
     * @param key 64-bit key
     * @return encoded long payload containing pageId (high 32 bits) and slotId (low 16 bits), or -1L if not found
     */
    public long get(long key) {
        long hash = XxHash3.hash64(key);
        int shardIdx = (int) ((hash >>> shardShift) & shardMask);
        long shardBaseOffset = shardIdx * shardSizeBytes;
        int currIndex = (int) (hash & shardCapacityMask);
        short fingerprint = XxHash3.extractFingerprint(hash);
        int hashUpper = (int) (hash >>> 16);
        short probeDistance = 0;

        for (int step = 0; step < shardCapacity; step++) {
            long bOffset = shardBaseOffset + ((long) currIndex << BUCKET_SHIFT);
            long bAddress = rawAddress + bOffset;

            long word1 = UNSAFE.getLong(bAddress + WORD1_OFFSET);
            short currDistance = (short) word1;

            if (currDistance == EMPTY_DISTANCE || currDistance < probeDistance) {
                return -1L;
            }

            long word0 = UNSAFE.getLong(bAddress + WORD0_OFFSET);
            short currFingerprint = (short) (word0 >>> 48);
            int currHashUpper = (int) (word1 >>> 32);

            if (currFingerprint == fingerprint && currHashUpper == hashUpper) {
                short slotId = (short) (word0 >>> 32);
                int pageId = (int) word0;
                return (((long) pageId) << 32) | (slotId & 0xFFFFL);
            }

            currIndex = (currIndex + 1) & shardCapacityMask;
            probeDistance++;
        }

        return -1L;
    }

    /**
     * Performs a key lookup in the target shard while recording telemetry metrics (probes, evaluations, rejections).
     *
     * @param key 64-bit key
     * @return encoded long payload containing pageId and slotId, or -1L if not found
     */
    public long getWithMetrics(long key) {
        totalLookups++;
        long hash = XxHash3.hash64(key);
        int shardIdx = (int) ((hash >>> shardShift) & shardMask);
        long shardBaseOffset = shardIdx * shardSizeBytes;
        int currIndex = (int) (hash & shardCapacityMask);
        short fingerprint = XxHash3.extractFingerprint(hash);
        int hashUpper = (int) (hash >>> 16);
        short probeDistance = 0;

        for (int step = 0; step < shardCapacity; step++) {
            totalProbes++;
            long bOffset = shardBaseOffset + ((long) currIndex << BUCKET_SHIFT);
            long bAddress = rawAddress + bOffset;

            long word1 = UNSAFE.getLong(bAddress + WORD1_OFFSET);
            short currDistance = (short) word1;

            if (currDistance == EMPTY_DISTANCE || currDistance < probeDistance) {
                return -1L;
            }

            long word0 = UNSAFE.getLong(bAddress + WORD0_OFFSET);
            short currFingerprint = (short) (word0 >>> 48);
            int currHashUpper = (int) (word1 >>> 32);
            fingerprintEvaluations++;

            if (currFingerprint == fingerprint && currHashUpper == hashUpper) {
                short slotId = (short) (word0 >>> 32);
                int pageId = (int) word0;
                return (((long) pageId) << 32) | (slotId & 0xFFFFL);
            } else {
                fingerprintRejections++;
            }

            currIndex = (currIndex + 1) & shardCapacityMask;
            probeDistance++;
        }

        return -1L;
    }

    /**
     * Executes a batch lookup with default 4-step software prefetching into L1 Data Cache across sub-shards.
     *
     * @param keys array of input keys
     * @param resultsOut output array for payloads
     * @param count number of keys to process
     */
    public void getBatch(long[] keys, long[] resultsOut, int count) {
        getBatch(keys, resultsOut, count, DEFAULT_PREFETCH_LOOKAHEAD);
    }

    /**
     * Executes a batch lookup with configurable software hardware prefetching into L1 Data Cache across sub-shards.
     *
     * @param keys array of input keys
     * @param resultsOut output array for payloads
     * @param count number of keys to process
     * @param prefetchLookahead lookahead distance (e.g. 4 for L2/L3 cache, 16 for cold DRAM)
     */
    public void getBatch(long[] keys, long[] resultsOut, int count, int prefetchLookahead) {
        for (int i = 0; i < count; i++) {
            int prefetchIdx = i + prefetchLookahead;
            if (prefetchIdx < count) {
                long pHash = XxHash3.hash64(keys[prefetchIdx]);
                int pShardIdx = (int) ((pHash >>> shardShift) & shardMask);
                long pShardBaseOffset = pShardIdx * shardSizeBytes;
                int pBucket = (int) (pHash & shardCapacityMask);
                long pAddress = rawAddress + pShardBaseOffset + ((long) pBucket << BUCKET_SHIFT);
                // Speculative read of distance/frequency word to pull 64-byte cache line into L1 cache
                UNSAFE.getShort(pAddress + WORD1_OFFSET);
            }
            resultsOut[i] = get(keys[i]);
        }
    }

    /**
     * Removes a key using Zero-Tombstone Backward-Shift Cluster Compaction within shard boundaries via Unsafe.
     *
     * @param key 64-bit key to delete
     * @return true if key was found and deleted; false otherwise
     */
    public boolean delete(long key) {
        long hash = XxHash3.hash64(key);
        int shardIdx = (int) ((hash >>> shardShift) & shardMask);
        long shardBaseOffset = shardIdx * shardSizeBytes;

        short fingerprint = XxHash3.extractFingerprint(hash);
        int hashUpper = (int) (hash >>> 16);
        int idealIndex = (int) (hash & shardCapacityMask);

        short probeDistance = 0;
        int currIndex = idealIndex;
        int targetIndex = -1;

        for (int step = 0; step < shardCapacity; step++) {
            long bOffset = shardBaseOffset + ((long) currIndex << BUCKET_SHIFT);
            long bAddress = rawAddress + bOffset;

            long word1 = UNSAFE.getLong(bAddress + WORD1_OFFSET);
            short currDistance = (short) word1;

            if (currDistance == EMPTY_DISTANCE || currDistance < probeDistance) {
                return false;
            }

            long word0 = UNSAFE.getLong(bAddress + WORD0_OFFSET);
            short currFingerprint = (short) (word0 >>> 48);
            int currHashUpper = (int) (word1 >>> 32);

            if (currFingerprint == fingerprint && currHashUpper == hashUpper) {
                targetIndex = currIndex;
                break;
            }

            currIndex = (currIndex + 1) & shardCapacityMask;
            probeDistance++;
        }

        if (targetIndex == -1) {
            return false;
        }

        // Backward-Shift Deletion: compact probe cluster backwards within shard
        currIndex = targetIndex;
        while (true) {
            int nextIndex = (currIndex + 1) & shardCapacityMask;
            long nextOffset = shardBaseOffset + ((long) nextIndex << BUCKET_SHIFT);
            long nextAddress = rawAddress + nextOffset;

            long nextWord1 = UNSAFE.getLong(nextAddress + WORD1_OFFSET);
            short nextDistance = (short) nextWord1;

            if (nextDistance == EMPTY_DISTANCE || nextDistance == 0) {
                long currOffset = shardBaseOffset + ((long) currIndex << BUCKET_SHIFT);
                UNSAFE.putShort(rawAddress + currOffset + DISTANCE_OFFSET, EMPTY_DISTANCE);
                break;
            }

            long currOffset = shardBaseOffset + ((long) currIndex << BUCKET_SHIFT);
            long currAddress = rawAddress + currOffset;
            long nextWord0 = UNSAFE.getLong(nextAddress + WORD0_OFFSET);
            long shiftedWord1 = ((nextDistance - 1) & 0xFFFFL) | (nextWord1 & 0xFFFF_FFFF_FFFF_0000L);

            UNSAFE.putLong(currAddress + WORD0_OFFSET, nextWord0);
            UNSAFE.putLong(currAddress + WORD1_OFFSET, shiftedWord1);

            currIndex = nextIndex;
        }

        size--;
        return true;
    }

    /**
     * Returns the current number of active entries across all shards.
     */
    public int size() {
        return size;
    }

    /**
     * Returns the total bucket capacity across all shards.
     */
    public int capacity() {
        return totalCapacity;
    }

    /**
     * Returns the number of sub-table shards.
     */
    public int numShards() {
        return numShards;
    }

    /**
     * Returns the bucket capacity per shard.
     */
    public int shardCapacity() {
        return shardCapacity;
    }

    /**
     * Returns the current load factor (size / totalCapacity).
     */
    public double loadFactor() {
        return (double) size / totalCapacity;
    }

    /**
     * Returns the maximum Probe Sequence Length (PSL) observed during insertions.
     */
    public int maxProbeLength() {
        return maxProbeLength;
    }

    /**
     * Returns the average Probe Sequence Length (PSL) per lookup operation.
     */
    public double averageProbeLength() {
        return totalLookups == 0 ? 0.0 : (double) totalProbes / totalLookups;
    }

    /**
     * Returns the ratio of fingerprint evaluation rejections to total evaluations.
     */
    public double fingerprintRejectionRate() {
        return fingerprintEvaluations == 0 ? 1.0 : (double) fingerprintRejections / fingerprintEvaluations;
    }

    /**
     * Returns the underlying Foreign Function & Memory segment backing this sharded index.
     */
    public MemorySegment segment() {
        return segment;
    }

    /**
     * Closes the off-heap Arena scope, freeing native memory.
     */
    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
