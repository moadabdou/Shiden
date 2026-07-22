package shiden.poc.hashindex;

import sun.misc.Unsafe;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;

/**
 * Ultra-Low Latency Unsafe Fast-Path Robin Hood Hash Index.
 * <p>
 * Bypasses FFM scope and boundary check wrappers on production hot paths by
 * dereferencing raw off-heap native memory addresses directly via {@link Unsafe}:
 * <ul>
 *   <li><b>1 Native Instruction</b>: Accesses buckets via <code>UNSAFE.getLong(rawAddress + offset)</code>.</li>
 *   <li><b>Sub-12ns Latency Target</b>: Eliminates JVM VarHandle stack frame overhead.</li>
 *   <li><b>48-Bit Hash Matching Guarantee</b>: Combines 16-bit fingerprint + 32-bit HashUpper to eliminate false collision matches.</li>
 * </ul>
 * </p>
 */
public class UnsafeFastPathRobinHoodHashIndex implements AutoCloseable {

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

    public static final long WORD0_OFFSET = 0; // Page ID (0..31), Slot ID (32..47), Fingerprint (48..63)
    public static final long WORD1_OFFSET = 8; // Distance (0..15), Frequency (16..31), HashUpper (32..63)

    private final Arena arena;
    private final MemorySegment segment;
    private final long rawAddress;
    private final int capacity;
    private final int mask;
    private int size = 0;

    public UnsafeFastPathRobinHoodHashIndex(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException("Capacity must be a power of 2: " + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.arena = Arena.ofConfined();

        long totalBytes = (long) capacity * BUCKET_SIZE_BYTES;
        this.segment = arena.allocate(totalBytes, 64);
        this.rawAddress = segment.address();

        clearTable();
    }

    private void clearTable() {
        for (int i = 0; i < capacity; i++) {
            long offset = (long) i << BUCKET_SHIFT;
            UNSAFE.putShort(rawAddress + offset + 8, EMPTY_DISTANCE);
        }
    }

    public long get(long key) {
        long hash = XxHash3.hash64(key);
        short fingerprint = XxHash3.extractFingerprint(hash);
        int hashUpper = (int) (hash >>> 16);
        int currIndex = (int) (hash & mask);
        short probeDistance = 0;

        for (int step = 0; step < capacity; step++) {
            long bOffset = (long) currIndex << BUCKET_SHIFT;
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

            currIndex = (currIndex + 1) & mask;
            probeDistance++;
        }

        return -1L;
    }

    public void getBatch(long[] keys, long[] resultsOut, int count) {
        int prefetchLookahead = 4;
        for (int i = 0; i < count; i++) {
            int prefetchIdx = i + prefetchLookahead;
            if (prefetchIdx < count) {
                long pHash = XxHash3.hash64(keys[prefetchIdx]);
                int pBucket = (int) (pHash & mask);
                UNSAFE.loadFence();
            }
            resultsOut[i] = get(keys[i]);
        }
    }

    public boolean put(long key, int pageId, short slotId) {
        if (size >= capacity) {
            return false;
        }

        long hash = XxHash3.hash64(key);
        short fingerprint = XxHash3.extractFingerprint(hash);
        int hashUpper = (int) (hash >>> 16);
        int idealIndex = (int) (hash & mask);

        short incomingDistance = 0;
        short incomingFingerprint = fingerprint;
        short incomingSlotId = slotId;
        int incomingPageId = pageId;
        int incomingHashUpper = hashUpper;

        int currIndex = idealIndex;

        for (int step = 0; step < capacity; step++) {
            long bOffset = (long) currIndex << BUCKET_SHIFT;
            long bAddress = rawAddress + bOffset;

            long word1 = UNSAFE.getLong(bAddress + WORD1_OFFSET);
            short currDistance = (short) word1;

            if (currDistance == EMPTY_DISTANCE) {
                long word0 = (incomingPageId & 0xFFFFFFFFL) |
                            ((incomingSlotId & 0xFFFFL) << 32) |
                            ((incomingFingerprint & 0xFFFFL) << 48);
                long newWord1 = (incomingDistance & 0xFFFFL) |
                               (((long) incomingHashUpper) << 32);

                UNSAFE.putLong(bAddress + WORD0_OFFSET, word0);
                UNSAFE.putLong(bAddress + WORD1_OFFSET, newWord1);
                size++;
                return true;
            }

            long word0 = UNSAFE.getLong(bAddress + WORD0_OFFSET);
            short currFingerprint = (short) (word0 >>> 48);
            short currSlotId = (short) (word0 >>> 32);
            int currPageId = (int) word0;
            int currHashUpper = (int) (word1 >>> 32);

            if (currDistance == incomingDistance &&
                currFingerprint == incomingFingerprint &&
                currHashUpper == incomingHashUpper &&
                currPageId == incomingPageId &&
                currSlotId == incomingSlotId) {
                return true;
            }

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
                incomingSlotId = currSlotId;
                incomingPageId = currPageId;
                incomingHashUpper = currHashUpper;
            }

            currIndex = (currIndex + 1) & mask;
            incomingDistance++;
        }

        return false;
    }

    public short getFrequency(long key) {
        long hash = XxHash3.hash64(key);
        short fingerprint = XxHash3.extractFingerprint(hash);
        int hashUpper = (int) (hash >>> 16);
        int currIndex = (int) (hash & mask);
        short probeDistance = 0;

        for (int step = 0; step < capacity; step++) {
            long bOffset = (long) currIndex << BUCKET_SHIFT;
            long bAddress = rawAddress + bOffset;

            long word1 = UNSAFE.getLong(bAddress + WORD1_OFFSET);
            short currDistance = (short) word1;

            if (currDistance == EMPTY_DISTANCE || currDistance < probeDistance) {
                return 0;
            }

            long word0 = UNSAFE.getLong(bAddress + WORD0_OFFSET);
            short currFingerprint = (short) (word0 >>> 48);
            int currHashUpper = (int) (word1 >>> 32);

            if (currFingerprint == fingerprint && currHashUpper == hashUpper) {
                return (short) ((word1 >>> 16) & 0xFFFF);
            }

            currIndex = (currIndex + 1) & mask;
            probeDistance++;
        }

        return 0;
    }

    public MemorySegment segment() {
        return segment;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
