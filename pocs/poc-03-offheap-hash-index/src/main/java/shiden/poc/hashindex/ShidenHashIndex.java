package shiden.poc.hashindex;

import java.lang.foreign.MemorySegment;

/**
 * Common abstraction for all Shiden Off-Heap Hash Index Engine implementations.
 */
public interface ShidenHashIndex extends AutoCloseable {

    /**
     * Inserts or updates a key-to-record mapping.
     */
    boolean put(long key, int pageId, short slotId);

    /**
     * Inserts or updates a key mapping directly using a pre-computed 64-bit hash.
     */
    boolean putByHash(long hash, int pageId, short slotId);

    /**
     * Looks up record pointer by key.
     */
    long get(long key);

    /**
     * Deletes record entry by key.
     */
    boolean delete(long key);

    /**
     * Reconstructs 100% exact original 64-bit hash for a bucket given its location and metadata.
     */
    long reconstructHash(int bucketIdx, short distance, short fingerprint, int hashUpper);

    /**
     * Returns current number of active entries.
     */
    int size();

    /**
     * Returns total bucket capacity.
     */
    int capacity();

    /**
     * Returns current load factor (size / capacity).
     */
    double loadFactor();

    /**
     * Returns direct raw native memory base address for Unsafe fast-path access.
     */
    long rawAddress();

    /**
     * Returns underlying FFM MemorySegment.
     */
    MemorySegment segment();

    /**
     * Frees native off-heap memory.
     */
    @Override
    void close();
}
