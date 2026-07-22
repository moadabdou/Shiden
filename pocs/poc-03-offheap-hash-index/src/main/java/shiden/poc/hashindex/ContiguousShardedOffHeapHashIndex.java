package shiden.poc.hashindex;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * 100% Pointer-Free Contiguous L2-Cache-Confined Sharded Off-Heap Hash Index.
 * <p>
 * Eliminates all Java object pointers, object arrays, and virtual method overheads
 * by laying out all sub-table shards contiguously in a single off-heap native MemorySegment.
 * </p>
 */
public class ContiguousShardedOffHeapHashIndex implements AutoCloseable {

    private final Arena arena;
    private final MemorySegment segment;
    private final int numShards;
    private final int shardMask;
    private final int shardShift;
    private final int shardCapacity;
    private final int shardCapacityMask;
    private final long shardSizeBytes;
    private final int totalCapacity;

    public ContiguousShardedOffHeapHashIndex(int totalCapacity, int numShards) {
        if ((totalCapacity & (totalCapacity - 1)) != 0 || (numShards & (numShards - 1)) != 0) {
            throw new IllegalArgumentException("Total capacity and numShards must be powers of 2.");
        }
        this.totalCapacity = totalCapacity;
        this.numShards = numShards;
        this.shardMask = numShards - 1;
        this.shardShift = Long.SIZE - Integer.numberOfTrailingZeros(numShards);

        this.shardCapacity = totalCapacity / numShards;
        this.shardCapacityMask = shardCapacity - 1;
        this.shardSizeBytes = (long) shardCapacity * OffHeapRobinHoodHashIndex.BUCKET_SIZE_BYTES;

        this.arena = Arena.ofConfined();
        long totalBytes = (long) totalCapacity * OffHeapRobinHoodHashIndex.BUCKET_SIZE_BYTES;
        this.segment = arena.allocate(totalBytes, 64);

        // Clear table
        for (int i = 0; i < totalCapacity; i++) {
            long offset = (long) i << OffHeapRobinHoodHashIndex.BUCKET_SHIFT;
            segment.set(ValueLayout.JAVA_SHORT, offset + OffHeapRobinHoodHashIndex.DISTANCE_OFFSET,
                    OffHeapRobinHoodHashIndex.EMPTY_DISTANCE);
        }
    }

    public boolean put(long key, int pageId, short slotId) {
        long hash = XxHash3.hash64(key);
        int shardIdx = (int) ((hash >>> shardShift) & shardMask);
        long shardBaseOffset = shardIdx * shardSizeBytes;
        int idealIndex = (int) (hash & shardCapacityMask);

        short incomingDistance = 0;
        short incomingFingerprint = XxHash3.extractFingerprint(hash);
        short incomingSlotId = slotId;
        int incomingPageId = pageId;

        int currIndex = idealIndex;

        for (int step = 0; step < shardCapacity; step++) {
            long bOffset = shardBaseOffset + ((long) currIndex << OffHeapRobinHoodHashIndex.BUCKET_SHIFT);
            long word1 = segment.get(ValueLayout.JAVA_LONG, bOffset + OffHeapRobinHoodHashIndex.WORD1_OFFSET);
            short currDistance = (short) word1;

            if (currDistance == OffHeapRobinHoodHashIndex.EMPTY_DISTANCE) {
                long word0 = (incomingPageId & 0xFFFFFFFFL) |
                            ((incomingSlotId & 0xFFFFL) << 32) |
                            ((incomingFingerprint & 0xFFFFL) << 48);
                long newWord1 = (incomingDistance & 0xFFFFL);

                segment.set(ValueLayout.JAVA_LONG, bOffset + OffHeapRobinHoodHashIndex.WORD0_OFFSET, word0);
                segment.set(ValueLayout.JAVA_LONG, bOffset + OffHeapRobinHoodHashIndex.WORD1_OFFSET, newWord1);
                return true;
            }

            long word0 = segment.get(ValueLayout.JAVA_LONG, bOffset + OffHeapRobinHoodHashIndex.WORD0_OFFSET);
            short currFingerprint = (short) (word0 >>> 48);
            short currSlotId = (short) (word0 >>> 32);
            int currPageId = (int) word0;

            if (currDistance == incomingDistance &&
                currFingerprint == incomingFingerprint &&
                currPageId == incomingPageId &&
                currSlotId == incomingSlotId) {
                return true;
            }

            if (currDistance < incomingDistance) {
                long newWord0 = (incomingPageId & 0xFFFFFFFFL) |
                               ((incomingSlotId & 0xFFFFL) << 32) |
                               ((incomingFingerprint & 0xFFFFL) << 48);
                long newWord1 = (incomingDistance & 0xFFFFL);

                segment.set(ValueLayout.JAVA_LONG, bOffset + OffHeapRobinHoodHashIndex.WORD0_OFFSET, newWord0);
                segment.set(ValueLayout.JAVA_LONG, bOffset + OffHeapRobinHoodHashIndex.WORD1_OFFSET, newWord1);

                incomingDistance = currDistance;
                incomingFingerprint = currFingerprint;
                incomingSlotId = currSlotId;
                incomingPageId = currPageId;
            }

            currIndex = (currIndex + 1) & shardCapacityMask;
            incomingDistance++;
        }

        return false;
    }

    public long get(long key) {
        long hash = XxHash3.hash64(key);
        int shardIdx = (int) ((hash >>> shardShift) & shardMask);
        long shardBaseOffset = shardIdx * shardSizeBytes;
        int currIndex = (int) (hash & shardCapacityMask);
        short fingerprint = XxHash3.extractFingerprint(hash);
        short probeDistance = 0;

        for (int step = 0; step < shardCapacity; step++) {
            long bOffset = shardBaseOffset + ((long) currIndex << OffHeapRobinHoodHashIndex.BUCKET_SHIFT);

            long word1 = segment.get(ValueLayout.JAVA_LONG, bOffset + OffHeapRobinHoodHashIndex.WORD1_OFFSET);
            short currDistance = (short) word1;

            if (currDistance == OffHeapRobinHoodHashIndex.EMPTY_DISTANCE || currDistance < probeDistance) {
                return -1L;
            }

            long word0 = segment.get(ValueLayout.JAVA_LONG, bOffset + OffHeapRobinHoodHashIndex.WORD0_OFFSET);
            short currFingerprint = (short) (word0 >>> 48);

            if (currFingerprint == fingerprint) {
                short slotId = (short) (word0 >>> 32);
                int pageId = (int) word0;
                return (((long) pageId) << 32) | (slotId & 0xFFFFL);
            }

            currIndex = (currIndex + 1) & shardCapacityMask;
            probeDistance++;
        }

        return -1L;
    }

    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
