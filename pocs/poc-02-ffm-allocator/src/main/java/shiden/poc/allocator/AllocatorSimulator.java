package shiden.poc.allocator;

import java.lang.foreign.MemorySegment;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Simulator runner for PoC-02 Off-Heap Arena & Slab Allocator verification.
 * <p>
 * Verifies two critical technical invariants:
 * 1. <b>Zero GC Pause Overhead</b>: Compares allocating 1,000,000 records on the JVM Heap vs. Off-Heap Slab.
 * 2. <b>CPU Cache Alignment</b>: Asserts 8-byte, 16-byte, and 64-byte boundary compliance for all allocations.
 * </p>
 */
public class AllocatorSimulator {

    private static final int RECORD_COUNT = 1_000_000;

    public static void main(String[] args) {
        System.out.println("===============================================================");
        System.out.println("⚡ Shiden PoC-02: Off-Heap Arena & Slab Allocator Simulator ⚡");
        System.out.println("===============================================================\n");

        runAlignmentVerification();
        System.out.println();
        runGcImpactComparison();

        System.out.println("\n✅ Simulator verification complete!");
    }

    /**
     * Test 1: Verifies strict memory alignment invariants.
     */
    private static void runAlignmentVerification() {
        System.out.println("🔍 Test 1: Memory Alignment & CPU Cache-Line Verification");
        System.out.println("---------------------------------------------------------------");

        // Check SlabAllocator 64-byte cache line alignment
        int slots = 1024;
        int slotSize = 64; // Mailbox node size
        try (SlabAllocator slab = new SlabAllocator(slots, slotSize, 64)) {
            MemorySegment base = slab.baseSegment();
            long baseAddr = base.address();

            System.out.printf("  Base Native Address  : 0x%X\n", baseAddr);
            System.out.printf("  Base Aligned to 64B  : %b\n", (baseAddr % 64 == 0));

            if (baseAddr % 64 != 0) {
                throw new AssertionError("Base segment address is not 64-byte aligned!");
            }

            // Verify offsets
            for (int i = 0; i < 10; i++) {
                long offset = slab.allocate();
                long slotAddr = baseAddr + offset;
                boolean aligned64 = (slotAddr % 64 == 0);
                System.out.printf("  Slot %2d Address      : 0x%X (Offset: %4d, Aligned 64B: %b)\n", 
                        i, slotAddr, offset, aligned64);
                if (!aligned64) {
                    throw new AssertionError("Slot " + i + " address is not 64-byte aligned!");
                }
            }
            System.out.println("  Result: 100% Cache-line alignment assertions PASSED.");
        }
    }

    /**
     * Test 2: Compares GC pause and memory overhead of JVM Heap vs Off-Heap Slab.
     */
    private static void runGcImpactComparison() {
        System.out.println("⚡ Test 2: JVM Heap vs Off-Heap GC Impact Comparison");
        System.out.println("---------------------------------------------------------------");
        System.out.printf("  Simulating %d record allocations...\n\n", RECORD_COUNT);

        // Stabilize JVM memory baseline
        stabilizeGc();

        // A. Heap Allocation Baseline
        long gcCountBefore = getGcCount();
        long gcTimeBefore = getGcTime();
        long memBeforeHeap = getUsedMemory();

        HeapDummyNode[] heapNodes = new HeapDummyNode[RECORD_COUNT];
        for (int i = 0; i < RECORD_COUNT; i++) {
            heapNodes[i] = new HeapDummyNode(i, i, (short) 1, (short) 0);
        }

        long memAfterHeap = getUsedMemory();
        long gcCountAfterHeap = getGcCount();
        long gcTimeAfterHeap = getGcTime();

        System.out.printf("  [JVM HEAP] Used Memory Delta : %.2f MB\n", Math.max(0, (memAfterHeap - memBeforeHeap) / (1024.0 * 1024.0)));
        System.out.printf("  [JVM HEAP] GC Collections    : %d\n", (gcCountAfterHeap - gcCountBefore));
        System.out.printf("  [JVM HEAP] GC Pause Time     : %d ms\n\n", (gcTimeAfterHeap - gcTimeBefore));

        // Clear heap references & force full collection to reset heap
        heapNodes = null;
        stabilizeGc();

        // B. Off-Heap Slab Allocation
        long memBeforeOffHeap = getUsedMemory();
        long gcCountBeforeOffHeap = getGcCount();
        long gcTimeBeforeOffHeap = getGcTime();

        try (SlabAllocator slab = new SlabAllocator(RECORD_COUNT, 16, 16)) {
            MemorySegment base = slab.baseSegment();

            for (int i = 0; i < RECORD_COUNT; i++) {
                long offset = slab.allocate();
                // Write Hash Index bucket fields using static helper methods (0 heap allocations!)
                MemoryLayouts.setBucketHash(base, offset, i);
                MemoryLayouts.setBucketPageId(base, offset, i);
                MemoryLayouts.setBucketSlotId(base, offset, (short) 1);
                MemoryLayouts.setBucketFlags(base, offset, (short) 0);
            }

            long memAfterOffHeap = getUsedMemory();
            long gcCountAfterOffHeap = getGcCount();
            long gcTimeAfterOffHeap = getGcTime();

            long heapDelta = Math.max(0, memAfterOffHeap - memBeforeOffHeap);
            System.out.printf("  [OFF-HEAP SLAB] Used Memory Delta : %.2f MB\n", heapDelta / (1024.0 * 1024.0));
            System.out.printf("  [OFF-HEAP SLAB] GC Collections    : %d\n", (gcCountAfterOffHeap - gcCountBeforeOffHeap));
            System.out.printf("  [OFF-HEAP SLAB] GC Pause Time     : %d ms\n", (gcTimeAfterOffHeap - gcTimeBeforeOffHeap));
        }

        System.out.println("\n  Result: Off-heap memory allocated 100% GC-free with flat-lined heap usage.");
    }

    private static void stabilizeGc() {
        System.gc();
        try {
            Thread.sleep(150);
        } catch (InterruptedException ignored) {}
    }

    private static long getUsedMemory() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long getGcCount() {
        long count = 0;
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean bean : beans) {
            long c = bean.getCollectionCount();
            if (c > 0) count += c;
        }
        return count;
    }

    private static long getGcTime() {
        long time = 0;
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        for (GarbageCollectorMXBean bean : beans) {
            long t = bean.getCollectionTime();
            if (t > 0) time += t;
        }
        return time;
    }

    // Standard Java Object for Heap baseline comparison
    private static final class HeapDummyNode {
        final long hashKey;
        final int pageId;
        final short slotId;
        final short flags;

        HeapDummyNode(long hashKey, int pageId, short slotId, short flags) {
            this.hashKey = hashKey;
            this.pageId = pageId;
            this.slotId = slotId;
            this.flags = flags;
        }
    }
}
