package shiden.poc.hashindex;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Head-to-Head JMH Micro-benchmark Suite: Safe FFM vs. Unsafe Fast-Path.
 * <p>
 * Evaluates execution time (ns/op) and throughput (ops/sec) under pure OpenJDK JMH conditions.
 * </p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class HeadToHeadJmhBenchmark {

    private OffHeapRobinHoodHashIndex ffmIndex;
    private UnsafeFastPathRobinHoodHashIndex unsafeIndex;

    private long[] keys;
    private int[] pageIds;
    private short[] slotIds;
    private long[] batchKeys;
    private long[] batchResults;

    private int keyIndex = 0;
    private int totalKeys;
    private Random random;

    @Setup(Level.Trial)
    public void setup() {
        int capacity = 131_072; // 2^17
        totalKeys = (int) (capacity * 0.85); // ~111,411 entries

        ffmIndex = new OffHeapRobinHoodHashIndex(capacity);
        unsafeIndex = new UnsafeFastPathRobinHoodHashIndex(capacity);

        keys = new long[totalKeys];
        pageIds = new int[totalKeys];
        slotIds = new short[totalKeys];

        random = new Random(42);
        for (int i = 0; i < totalKeys; i++) {
            keys[i] = random.nextLong();
            pageIds[i] = i + 1;
            slotIds[i] = (short) (i & 0x7FFF);

            ffmIndex.put(keys[i], pageIds[i], slotIds[i]);
            unsafeIndex.put(keys[i], pageIds[i], slotIds[i]);
        }

        batchKeys = new long[32];
        batchResults = new long[32];
        for (int i = 0; i < 32; i++) {
            batchKeys[i] = keys[i];
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (ffmIndex != null) ffmIndex.close();
        if (unsafeIndex != null) unsafeIndex.close();
    }

    // --- 1. GET (HOT LOOKUP) HEAD-TO-HEAD ---

    @Benchmark
    public void benchmarkFfmGet(Blackhole bh) {
        int idx = keyIndex++;
        if (keyIndex >= totalKeys) keyIndex = 0;
        bh.consume(ffmIndex.get(keys[idx]));
    }

    @Benchmark
    public void benchmarkUnsafeGet(Blackhole bh) {
        int idx = keyIndex++;
        if (keyIndex >= totalKeys) keyIndex = 0;
        bh.consume(unsafeIndex.get(keys[idx]));
    }

    // --- 2. GET BATCH HEAD-TO-HEAD ---

    @Benchmark
    public void benchmarkUnsafeBatchGetPrefetched(Blackhole bh) {
        unsafeIndex.getBatch(batchKeys, batchResults, 32);
        bh.consume(batchResults);
    }

    // --- 3. PUT (INSERTION) HEAD-TO-HEAD ---

    @Benchmark
    public void benchmarkFfmPut(Blackhole bh) {
        long newKey = random.nextLong();
        bh.consume(ffmIndex.put(newKey, 1, (short) 1));
    }

    @Benchmark
    public void benchmarkUnsafePut(Blackhole bh) {
        long newKey = random.nextLong();
        bh.consume(unsafeIndex.put(newKey, 1, (short) 1));
    }
}
