package shiden.poc.hashindex;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Production-Optimized Micro-benchmark Suite for PoC-03 (RFC-002 Enhancements).
 * <p>
 * Evaluates the performance impact of all 5 strategic engineering recommendations:
 * <ul>
 *   <li><b>Unsafe Fast-Path Direct Address Bypass</b>: Direct native memory dereferencing.</li>
 *   <li><b>Batch Hardware Memory Prefetching</b>: L1 cache prefetching for batch operations.</li>
 *   <li><b>TinyLFU Eviction Frequency Counter</b>: Integrated access frequency tracking inside 16-byte bucket.</li>
 *   <li><b>Adaptive Rehashing Pacing</b>: Dynamic tax scaling during heavy write bursts.</li>
 * </ul>
 * </p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class ProductionOptimizedHashIndexBenchmark {

    private UnsafeFastPathRobinHoodHashIndex unsafeIndex;
    private IncrementalRehashIndex adaptiveRehashIndex;

    private long[] keys;
    private long[] batchKeys;
    private long[] batchResults;
    private int keyIndex = 0;
    private int totalKeys;
    private Random random;

    @Setup(Level.Trial)
    public void setup() {
        int capacity = 131_072; // 2^17
        totalKeys = (int) (capacity * 0.85); // 85% load factor (~111,411 entries)

        unsafeIndex = new UnsafeFastPathRobinHoodHashIndex(capacity);
        keys = new long[totalKeys];

        random = new Random(42);
        for (int i = 0; i < totalKeys; i++) {
            keys[i] = random.nextLong();
            unsafeIndex.put(keys[i], i + 1, (short) (i & 0x7FFF));
        }

        batchKeys = new long[32];
        batchResults = new long[32];
        for (int i = 0; i < 32; i++) {
            batchKeys[i] = keys[i];
        }

        adaptiveRehashIndex = new IncrementalRehashIndex(65_536, 0.85);
        for (int i = 0; i < 50_000; i++) {
            adaptiveRehashIndex.put(random.nextLong(), i + 1, (short) (i & 0x7FFF));
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (unsafeIndex != null) unsafeIndex.close();
        if (adaptiveRehashIndex != null) adaptiveRehashIndex.close();
    }

    /**
     * 1. Unsafe Fast-Path Direct Address Bounds-Bypass Lookup (1 Native MOVQ Instruction).
     */
    @Benchmark
    public void benchmarkUnsafeFastPathGet(Blackhole bh) {
        int idx = keyIndex++;
        if (keyIndex >= totalKeys) keyIndex = 0;
        long res = unsafeIndex.get(keys[idx]);
        bh.consume(res);
    }

    /**
     * 2. Batch Hardware Memory Prefetched Lookups (32 Keys Batch).
     */
    @Benchmark
    public void benchmarkBatchGetPrefetched(Blackhole bh) {
        unsafeIndex.getBatch(batchKeys, batchResults, 32);
        bh.consume(batchResults);
    }

    /**
     * 3. Integrated TinyLFU Eviction Frequency Counter Check.
     */
    @Benchmark
    public void benchmarkTinyLFUFrequencyHit(Blackhole bh) {
        int idx = keyIndex++;
        if (keyIndex >= totalKeys) keyIndex = 0;
        short freq = unsafeIndex.getFrequency(keys[idx]);
        bh.consume(freq);
    }

    /**
     * 4. Adaptive Rehash Dynamic Pacing Write Tax.
     */
    @Benchmark
    public void benchmarkAdaptiveRehashWriteTax(Blackhole bh) {
        long newKey = random.nextLong();
        boolean res = adaptiveRehashIndex.put(newKey, 1, (short) 1);
        bh.consume(res);
    }
}
