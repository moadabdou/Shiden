package shiden.poc.allocator;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Thread-Confined $O(1)$ Off-Heap Fixed-Size Slab Allocator.
 * <p>
 * Designed for fixed-size metadata structures (Hash Index buckets, Mailbox nodes, Extent descriptors).
 * Features simple, clean memory management:
 * <ul>
 *   <li><b>100% Off-Heap Bitmaps</b>: Allocator bitmaps reside off-heap in 64-byte cache-aligned native memory, eliminating JVM Heap false sharing.</li>
 *   <li><b>Cursor-Based Wrapping</b>: Simple branch-based cursor wrap-around ({@code if (++i == numWords) i = 0}) guaranteeing 100% correct slot traversal without padding hacks.</li>
 *   <li><b>ALU Bitshift Offset Calculation</b>: Slot offsets use bitwise left-shifts ({@code << slotShift}) for power-of-two slot sizes.</li>
 *   <li><b>Hardware Bit Scan Intrinsics</b>: Uses {@link Long#numberOfTrailingZeros(long)} ({@code TZCNT}) for single-cycle slot discovery.</li>
 * </ul>
 * </p>
 */
public class SlabAllocator implements AutoCloseable {

    private final Arena arena;
    private final MemorySegment baseSegment;
    private final MemorySegment bitmapsSegment;
    private final int slotSize;
    private final int totalSlots;
    private final long alignment;
    private final int numWords;
    private final int slotShift;       // Shift offset for power-of-2 slot sizes (16 -> 4, 32 -> 5, 64 -> 6)

    private int freeSlotsCount;
    private int lastAllocatedWordIndex = 0; // Cursor to prevent O(N) scanning on full slabs

    /**
     * Creates a SlabAllocator.
     *
     * @param totalSlots Total number of fixed-size slots to allocate.
     * @param slotSize Size in bytes of each slot.
     * @param alignment Memory alignment boundary (e.g., 8, 16, 64 bytes).
     */
    public SlabAllocator(int totalSlots, int slotSize, long alignment) {
        if (totalSlots <= 0 || slotSize <= 0) {
            throw new IllegalArgumentException("Slot count and size must be positive.");
        }
        if ((alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("Alignment must be a power of 2: " + alignment);
        }

        this.arena = Arena.ofConfined();
        this.totalSlots = totalSlots;
        this.slotSize = slotSize;
        this.alignment = alignment;
        this.freeSlotsCount = totalSlots;

        // Bit shift for power-of-two slot sizes
        if ((slotSize & (slotSize - 1)) == 0) {
            this.slotShift = Integer.numberOfTrailingZeros(slotSize);
        } else {
            this.slotShift = -1; // Fallback to multiplication
        }

        this.numWords = (totalSlots + 63) / 64;

        // Allocate 64-byte cache-line aligned off-heap segment for bitmaps
        long bitmapBytes = (long) numWords * 8L;
        this.bitmapsSegment = arena.allocate(bitmapBytes, 64);

        // Allocate off-heap payload segment respecting requested alignment
        long totalBytes = (long) totalSlots * slotSize;
        this.baseSegment = arena.allocate(totalBytes, alignment);
    }

    /**
     * Creates a SlabAllocator with 64-byte CPU cache-line default alignment.
     */
    public SlabAllocator(int totalSlots, int slotSize) {
        this(totalSlots, slotSize, 64);
    }

    /**
     * Allocates a slot and returns its raw byte offset within the base segment.
     *
     * @return Raw byte offset within the base segment.
     * @throws OutOfMemoryError if no free slots are available.
     */
    public long allocate() {
        if (freeSlotsCount == 0) {
            throw new OutOfMemoryError("SlabAllocator out of free slots! Capacity: " + totalSlots);
        }

        int i = lastAllocatedWordIndex;

        for (int step = 0; step < numWords; step++) {
            long wordOffset = (long) i << 3; // i * 8 bytes
            long word = bitmapsSegment.get(ValueLayout.JAVA_LONG, wordOffset);
            long freeBits = ~word; // Invert: 1s represent free slots

            if (freeBits != 0) {
                int bitIndex = Long.numberOfTrailingZeros(freeBits);
                int slotIndex = (i << 6) + bitIndex; // i * 64

                if (slotIndex < totalSlots) {
                    long updatedWord = word | (1L << bitIndex);
                    bitmapsSegment.set(ValueLayout.JAVA_LONG, wordOffset, updatedWord);
                    freeSlotsCount--;

                    // Advance cursor cleanly
                    if (updatedWord == -1L) {
                        int next = i + 1;
                        lastAllocatedWordIndex = (next == numWords) ? 0 : next;
                    } else {
                        lastAllocatedWordIndex = i;
                    }

                    // Compute offset via fast ALU bitshift or multiplication
                    return (slotShift != -1) ? ((long) slotIndex << slotShift) : ((long) slotIndex * slotSize);
                }
            }

            // Simple, clean wrap-around
            if (++i == numWords) {
                i = 0;
            }
        }

        throw new OutOfMemoryError("SlabAllocator allocation full condition.");
    }

    /**
     * Frees a slot given its byte offset.
     *
     * @param offset Byte offset returned by {@link #allocate()}.
     */
    public void free(long offset) {
        int slotIndex = (slotShift != -1) ? (int) (offset >>> slotShift) : (int) (offset / slotSize);
        if (slotIndex < 0 || slotIndex >= totalSlots) {
            throw new IllegalArgumentException("Invalid offset for deallocation: " + offset);
        }

        int wordIndex = slotIndex >> 6;  // slotIndex / 64
        int bitIndex = slotIndex & 63;   // slotIndex % 64
        long wordOffset = (long) wordIndex << 3;

        long word = bitmapsSegment.get(ValueLayout.JAVA_LONG, wordOffset);
        long mask = 1L << bitIndex;
        if ((word & mask) == 0) {
            throw new IllegalStateException("Double free detected at offset " + offset + " (Slot " + slotIndex + ")");
        }

        bitmapsSegment.set(ValueLayout.JAVA_LONG, wordOffset, word & ~mask); // Mark free
        freeSlotsCount++;

        // Reset cursor to this word if lower
        if (wordIndex < lastAllocatedWordIndex) {
            lastAllocatedWordIndex = wordIndex;
        }
    }

    /**
     * Returns the base off-heap {@link MemorySegment}.
     */
    public MemorySegment baseSegment() {
        return baseSegment;
    }

    /**
     * Returns slot size in bytes.
     */
    public int slotSize() {
        return slotSize;
    }

    /**
     * Returns total slot capacity.
     */
    public int totalSlots() {
        return totalSlots;
    }

    /**
     * Returns currently available free slots count.
     */
    public int freeSlots() {
        return freeSlotsCount;
    }

    /**
     * Returns currently occupied slots count.
     */
    public int occupiedSlots() {
        return totalSlots - freeSlotsCount;
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
