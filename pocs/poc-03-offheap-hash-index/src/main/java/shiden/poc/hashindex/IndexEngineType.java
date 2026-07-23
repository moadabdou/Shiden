package shiden.poc.hashindex;

/**
 * Enumeration of available Off-Heap Hash Index Engine implementations in Shiden.
 */
public enum IndexEngineType {
    /**
     * Safe FFM-backed monolithic Robin Hood index (OffHeapRobinHoodHashIndex).
     */
    SAFE_FFM_MONOLITHIC,

    /**
     * Unsafe fast-path monolithic Robin Hood index (UnsafeFastPathRobinHoodHashIndex).
     */
    UNSAFE_FASTPATH_MONOLITHIC,

    /**
     * Unsafe 512KB L2-cache-confined contiguous sharded index (ContiguousShardedOffHeapHashIndex).
     */
    UNSAFE_CONTIGUOUS_SHARDED
}
