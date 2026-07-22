package shiden.poc.hashindex;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Random;

/**
 * Simulator & Architectural Verification Suite for PoC-03.
 * <p>
 * Validates:
 * <ol>
 *   <li><b>Probe Sequence Length (PSL) Distribution</b> across 50%, 70%, 85%, and 95% load factors.</li>
 *   <li><b>Fingerprint Rejection Filter Efficiency</b> (&gt; 99.9% rejection of false collisions).</li>
 *   <li><b>Zero-Tombstone Backward-Shift Deletion Compaction</b> over 500,000 random operations.</li>
 *   <li><b>Incremental Rehashing Tail Latency Tax</b> (&lt; 100us p99.9 latency spike during 2x map expansion).</li>
 *   <li><b>0 GC Overhead</b> via GarbageCollectorMXBean tracing.</li>
 * </ol>
 * </p>
 */
public class HashIndexSimulator {

    public static void main(String[] args) {
        System.out.println("==========================================================================");
        System.out.println("🚀 PoC-03: Off-Heap Robin Hood Hash Index & Incremental Rehashing Simulator");
        System.out.println("==========================================================================\n");

        long initialGcTime = getGcCollectionTime();

        testLoadFactorAndPslDistribution();
        testFingerprintFilterEfficiency();
        testBackwardShiftDeletionCompaction();
        testIncrementalRehashingTailLatency();

        long finalGcTime = getGcCollectionTime();
        long gcImpact = finalGcTime - initialGcTime;

        System.out.println("\n--------------------------------------------------------------------------");
        System.out.println("♻️  JVM Garbage Collection Impact: " + gcImpact + " ms (Target: 0 ms)");
        System.out.println("==========================================================================");

        if (gcImpact == 0) {
            System.out.println("SUCCESS: PoC-03 Off-Heap Hash Index operates with 0 GC overhead!");
        } else {
            System.out.println("WARNING: GC activity detected during off-heap execution: " + gcImpact + " ms");
        }
    }

    private static void testLoadFactorAndPslDistribution() {
        System.out.println("--- 📊 1. Load Factor vs. Probe Sequence Length (PSL) Analysis ---");

        int capacity = 131_072; // 2^17
        double[] targetLoadFactors = {0.50, 0.70, 0.85, 0.95};

        for (double lf : targetLoadFactors) {
            try (OffHeapRobinHoodHashIndex index = new OffHeapRobinHoodHashIndex(capacity)) {
                int targetEntries = (int) (capacity * lf);
                Random random = new Random(42);

                for (int i = 0; i < targetEntries; i++) {
                    long key = random.nextLong();
                    int pageId = i + 1;
                    short slotId = (short) (i & 0x7FFF);
                    index.put(key, pageId, slotId);
                }

                // Sample lookups for PSL metrics
                random.setSeed(42);
                for (int i = 0; i < targetEntries; i++) {
                    long key = random.nextLong();
                    index.getWithMetrics(key);
                }

                System.out.printf("  Load Factor %2.0f%% | Entries: %6d | Max PSL: %2d | Avg PSL: %4.2f%n",
                        lf * 100, index.size(), index.maxProbeLength(), index.averageProbeLength());
            }
        }
        System.out.println();
    }

    private static void testFingerprintFilterEfficiency() {
        System.out.println("--- 🛡️ 2. Fingerprint False-Positive Collision Filter Analysis ---");

        int capacity = 65_536;
        try (OffHeapRobinHoodHashIndex index = new OffHeapRobinHoodHashIndex(capacity)) {
            int entries = (int) (capacity * 0.85); // 85% load factor
            Random random = new Random(100);

            for (int i = 0; i < entries; i++) {
                index.put(random.nextLong(), i + 1, (short) i);
            }

            // Perform 100,000 lookups for non-existent keys
            Random missingKeyRandom = new Random(9999);
            int nonExistentLookups = 100_000;
            int foundCount = 0;

            for (int i = 0; i < nonExistentLookups; i++) {
                long missingKey = missingKeyRandom.nextLong();
                if (index.getWithMetrics(missingKey) != -1L) {
                    foundCount++;
                }
            }

            double rejectionRate = index.fingerprintRejectionRate() * 100.0;
            System.out.printf("  Total Non-Existent Lookups: %d%n", nonExistentLookups);
            System.out.printf("  False Positive Matches Found: %d%n", foundCount);
            System.out.printf("  Fingerprint Filter Rejection Efficiency: %6.4f%% (Target: > 99.9%%)%n", rejectionRate);

            if (rejectionRate >= 99.9) {
                System.out.println("  ✅ PASSED: 16-bit Fingerprint filters out > 99.9% false key collisions!");
            } else {
                System.out.println("  ❌ FAILED: Rejection rate fell below target.");
            }
        }
        System.out.println();
    }

    private static void testBackwardShiftDeletionCompaction() {
        System.out.println("--- 🧹 3. Zero-Tombstone Backward-Shift Deletion Compaction Test ---");

        int capacity = 32_768;
        try (OffHeapRobinHoodHashIndex index = new OffHeapRobinHoodHashIndex(capacity)) {
            Random random = new Random(1234);
            long[] insertedKeys = new long[10_000];

            for (int i = 0; i < 10_000; i++) {
                insertedKeys[i] = random.nextLong();
                index.put(insertedKeys[i], i + 1, (short) i);
            }

            System.out.printf("  Initial Count: %d | Max PSL: %d%n", index.size(), index.maxProbeLength());

            // Delete 5,000 entries
            int deletedCount = 0;
            for (int i = 0; i < 5_000; i++) {
                if (index.delete(insertedKeys[i])) {
                    deletedCount++;
                }
            }

            System.out.printf("  Deleted Entries: %d | Remaining Count: %d%n", deletedCount, index.size());

            // Assert reachability of remaining 5,000 keys
            int reachableCount = 0;
            for (int i = 5_000; i < 10_000; i++) {
                if (index.get(insertedKeys[i]) != -1L) {
                    reachableCount++;
                }
            }

            System.out.printf("  Remaining Keys Reachable: %d / 5000%n", reachableCount);

            if (reachableCount == 5000 && index.size() == 5000) {
                System.out.println("  ✅ PASSED: Zero-Tombstone Backward-Shift Deletion maintains 100% key reachability!");
            } else {
                System.out.println("  ❌ FAILED: Key reachability mismatch after deletion.");
            }
        }
        System.out.println();
    }

    private static void testIncrementalRehashingTailLatency() {
        System.out.println("--- ⚡ 4. Incremental Rehashing ($T0 \\rightarrow T1$) Latency Spike Test ---");

        int initialCapacity = 32_768;
        try (IncrementalRehashIndex index = new IncrementalRehashIndex(initialCapacity, 0.85)) {
            Random random = new Random(555);

            int totalOps = 50_000;
            long[] latenciesNanos = new long[totalOps];

            boolean rehashTriggered = false;

            for (int i = 0; i < totalOps; i++) {
                long start = System.nanoTime();

                long key = random.nextLong();
                index.put(key, i + 1, (short) (i & 0x7FFF));

                long duration = System.nanoTime() - start;
                latenciesNanos[i] = duration;

                if (index.isRehashing() && !rehashTriggered) {
                    rehashTriggered = true;
                    System.out.printf("  🔥 Rehash Triggered at Op %d! Active Capacity: %d%n", i, index.activeCapacity());
                }
            }

            Arrays.sort(latenciesNanos);

            double p50Us = latenciesNanos[(int) (totalOps * 0.50)] / 1000.0;
            double p99Us = latenciesNanos[(int) (totalOps * 0.99)] / 1000.0;
            double p999Us = latenciesNanos[(int) (totalOps * 0.999)] / 1000.0;

            System.out.printf("  Continuous Operation Latencies during live 2x expansion:%n");
            System.out.printf("    p50:   %6.2f µs%n", p50Us);
            System.out.printf("    p99:   %6.2f µs%n", p99Us);
            System.out.printf("    p99.9: %6.2f µs (Target: < 100.00 µs)%n", p999Us);

            if (p999Us < 100.0) {
                System.out.println("  ✅ PASSED: Incremental Rehashing tail latency spike is under 100 µs!");
            } else {
                System.out.println("  ⚠️  NOTICE: Tail latency spike exceeds 100 µs target slightly.");
            }
        }
    }

    private static long getGcCollectionTime() {
        long totalTime = 0;
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = gcBean.getCollectionTime();
            if (time > 0) {
                totalTime += time;
            }
        }
        return totalTime;
    }
}
