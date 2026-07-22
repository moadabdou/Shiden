package shiden.poc.hashindex;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Comparative JMH Benchmark: Monolithic vs. Contiguous L2-Cache-Confined Sharded Hash Index @ 1,000,000 Key Scale.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class ShardedHashIndexBenchmark {

    private static final int TOTAL_CAPACITY = 1_048_576; // 1M slots
    private static final int NUM_SHARDS = 32;            // 32 shards x 32,768 slots (512KB per shard)

    private OffHeapRobinHoodHashIndex monolithicIndex;
    private ContiguousShardedOffHeapHashIndex contiguousShardedIndex;

    private long[] keys;
    private int keyIndex = 0;
    private int totalKeys;

    @Setup(Level.Trial)
    public void setup() {
        totalKeys = (int) (TOTAL_CAPACITY * 0.85); // 85% load factor (~891,289 keys)
        keys = new long[totalKeys];

        monolithicIndex = new OffHeapRobinHoodHashIndex(TOTAL_CAPACITY);
        contiguousShardedIndex = new ContiguousShardedOffHeapHashIndex(TOTAL_CAPACITY, NUM_SHARDS);

        Random random = new Random(42);
        for (int i = 0; i < totalKeys; i++) {
            keys[i] = random.nextLong();
            monolithicIndex.put(keys[i], i + 1, (short) (i & 0x7FFF));
            contiguousShardedIndex.put(keys[i], i + 1, (short) (i & 0x7FFF));
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (monolithicIndex != null) monolithicIndex.close();
        if (contiguousShardedIndex != null) contiguousShardedIndex.close();
    }

    /**
     * Monolithic 16.7MB Hash Table Lookup @ 1,000,000 Key Scale.
     */
    @Benchmark
    public void benchmarkMonolithic_1M_Get(Blackhole bh) {
        int idx = keyIndex++;
        if (keyIndex >= totalKeys) keyIndex = 0;
        long res = monolithicIndex.get(keys[idx]);
        bh.consume(res);
    }

    /**
     * Contiguous L2-Cache-Confined Sharded Hash Table Lookup @ 1,000,000 Key Scale (100% Off-Heap 32 x 512KB Shards).
     */
    @Benchmark
    public void benchmarkContiguousSharded_1M_Get(Blackhole bh) {
        int idx = keyIndex++;
        if (keyIndex >= totalKeys) keyIndex = 0;
        long res = contiguousShardedIndex.get(keys[idx]);
        bh.consume(res);
    }
}
