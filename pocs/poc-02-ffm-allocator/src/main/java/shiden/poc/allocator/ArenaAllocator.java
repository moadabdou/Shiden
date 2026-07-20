package shiden.poc.allocator;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Thread-confined Arena Allocator implementing a fast bump/linear allocation strategy.
 * <p>
 * Allocates memory sequentially from off-heap native RAM via Java 21's FFM API {@link Arena#ofConfined()}.
 * Individual allocations cannot be freed independently; all allocated memory is reclaimed atomically when
 * the underlying Arena is closed.
 * </p>
 */
public class ArenaAllocator implements AutoCloseable {

    private final Arena arena;
    private final MemorySegment baseSegment;
    private final long capacity;
    private long offset;

    /**
     * Creates an ArenaAllocator with the specified byte capacity backed by a confined FFM Arena.
     *
     * @param capacity Total byte size of native memory to allocate off-heap.
     */
    public ArenaAllocator(long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        this.arena = Arena.ofConfined();
        this.capacity = capacity;
        this.baseSegment = arena.allocate(capacity, 64); // 64-byte CPU cache line aligned base
        this.offset = 0;
    }

    /**
     * Allocates a contiguous block of native memory of the given size.
     *
     * @param bytes Number of bytes to allocate.
     * @param alignment Required alignment boundary (e.g., 8, 16, 64 bytes).
     * @return Raw byte offset within the base segment.
     */
    public long allocate(long bytes, long alignment) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("Allocation bytes must be positive: " + bytes);
        }

        // Align current offset to alignment boundary
        long alignedOffset = (offset + (alignment - 1)) & ~(alignment - 1);
        long newOffset = alignedOffset + bytes;

        if (newOffset > capacity) {
            throw new OutOfMemoryError("ArenaAllocator out of native memory! Required: " 
                    + newOffset + "B, Capacity: " + capacity + "B");
        }

        this.offset = newOffset;
        return alignedOffset;
    }

    /**
     * Allocates a contiguous block of native memory with 8-byte default alignment.
     *
     * @param bytes Number of bytes to allocate.
     * @return Raw byte offset within the base segment.
     */
    public long allocate(long bytes) {
        return allocate(bytes, 8);
    }

    /**
     * Returns the base off-heap {@link MemorySegment} managed by this allocator.
     */
    public MemorySegment baseSegment() {
        return baseSegment;
    }

    /**
     * Returns the total capacity in bytes.
     */
    public long capacity() {
        return capacity;
    }

    /**
     * Returns the current allocated bytes.
     */
    public long allocatedBytes() {
        return offset;
    }

    /**
     * Returns the remaining available bytes.
     */
    public long remainingBytes() {
        return capacity - offset;
    }

    /**
     * Resets the bump pointer back to 0 without freeing the native segment.
     * Allows instant reuse of the underlying memory.
     */
    public void reset() {
        this.offset = 0;
    }

    /**
     * Closes the underlying confined Arena, atomically invalidating and freeing all native memory.
     */
    @Override
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}
