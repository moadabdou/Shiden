package shiden.poc.allocator;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

/**
 * Rigorous JMH Micro-benchmark measuring realistic allocation latency, read/write throughput,
 * and deallocation efficiency of Shiden's Off-Heap Allocator primitives vs. JVM Heap instantiation.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class AllocatorBenchmark {

    private static final int SLAB_CAPACITY = 65_536; // 64K slots
    private SlabAllocator slabAllocator;
    private ArenaAllocator arenaAllocator;
    private MemorySegment slabBaseSegment;
    
    // Batch of pre-allocated offsets for isolated read/write benchmarking
    private long[] sampleOffsets;
    private int offsetIndex = 0;
    private long counter = 0;

    @Setup(Level.Trial)
    public void setup() {
        slabAllocator = new SlabAllocator(SLAB_CAPACITY, 16, 16);
        slabBaseSegment = slabAllocator.baseSegment();
        arenaAllocator = new ArenaAllocator(64 * 1024 * 1024); // 64MB Arena

        // Pre-allocate 1,000 slots to test realistic field read/write throughput
        sampleOffsets = new long[1_000];
        for (int i = 0; i < sampleOffsets.length; i++) {
            sampleOffsets[i] = slabAllocator.allocate();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        slabAllocator.close();
        arenaAllocator.close();
    }

    /**
     * Baseline: Heap allocation of a Java Object consumed by Blackhole.
     */
    @Benchmark
    public void testHeapObjectAllocation(Blackhole bh) {
        long id = ++counter;
        bh.consume(new DummyHeapRecord(id, (int) id, (short) 1));
    }

    /**
     * Benchmark 1: Pure Arena Linear Bump Allocation (O(1)).
     */
    @Benchmark
    public void testArenaBumpAllocation(Blackhole bh) {
        long offset = arenaAllocator.allocate(16, 16);
        if (arenaAllocator.remainingBytes() < 64) {
            arenaAllocator.reset();
        }
        bh.consume(offset);
    }

    /**
     * Benchmark 2: Pure Off-Heap Slab Allocation + Free Cycle (O(1) Bitmap).
     */
    @Benchmark
    public void testSlabAllocateAndFree(Blackhole bh) {
        long offset = slabAllocator.allocate();
        bh.consume(offset);
        slabAllocator.free(offset);
    }

    /**
     * Benchmark 3: Pure Direct Off-Heap Primitive Field Write (VarHandle/MemorySegment).
     */
    @Benchmark
    public void testOffHeapRecordWrite(Blackhole bh) {
        long id = ++counter;
        long offset = sampleOffsets[(int) (id % sampleOffsets.length)];

        MemoryLayouts.setBucketHash(slabBaseSegment, offset, id);
        MemoryLayouts.setBucketPageId(slabBaseSegment, offset, (int) id);
        MemoryLayouts.setBucketSlotId(slabBaseSegment, offset, (short) 1);
        MemoryLayouts.setBucketFlags(slabBaseSegment, offset, (short) 0);
        bh.consume(offset);
    }

    /**
     * Benchmark 4: Pure Direct Off-Heap Primitive Field Read.
     */
    @Benchmark
    public void testOffHeapRecordRead(Blackhole bh) {
        int idx = (int) (++counter % sampleOffsets.length);
        long offset = sampleOffsets[idx];

        long hash = MemoryLayouts.getBucketHash(slabBaseSegment, offset);
        int pageId = MemoryLayouts.getBucketPageId(slabBaseSegment, offset);
        short slotId = MemoryLayouts.getBucketSlotId(slabBaseSegment, offset);

        bh.consume(hash);
        bh.consume(pageId);
        bh.consume(slotId);
    }

    /**
     * Benchmark 5: Full Combined Cycle (Slab Allocation + 4 Field Writes + Free).
     */
    @Benchmark
    public void testFullSlabAllocWriteFreeCycle(Blackhole bh) {
        long id = ++counter;
        long offset = slabAllocator.allocate();

        MemoryLayouts.setBucketHash(slabBaseSegment, offset, id);
        MemoryLayouts.setBucketPageId(slabBaseSegment, offset, (int) id);
        MemoryLayouts.setBucketSlotId(slabBaseSegment, offset, (short) 1);
        MemoryLayouts.setBucketFlags(slabBaseSegment, offset, (short) 0);

        slabAllocator.free(offset);
        bh.consume(offset);
    }

    private static final class DummyHeapRecord {
        final long hash;
        final int pageId;
        final short slotId;

        DummyHeapRecord(long hash, int pageId, short slotId) {
            this.hash = hash;
            this.pageId = pageId;
            this.slotId = slotId;
        }
    }
}
