package shiden.poc;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import shiden.poc.ring.ConsistentHashRing;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class HashRingBenchmark {

    @Param({"10", "100", "500"})
    public int vnodes;

    @Param({"MURMUR3", "JDK_HASHCODE"})
    public String hashType;

    @Param({"CONSISTENT", "SLOTS"})
    public String routingMode;

    private ConsistentHashRing ring;
    private String[] keys;

    @Setup(Level.Trial)
    public void setup() {
        int slots = "SLOTS".equals(routingMode) ? 1024 : 0;
        ring = new ConsistentHashRing(
                ConsistentHashRing.HashType.valueOf(hashType),
                vnodes,
                slots
        );
        for (int i = 1; i <= 5; i++) {
            ring.addNode("node-" + i);
        }

        keys = new String[10_000];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = "key-" + i;
        }
    }

    @Benchmark
    @Threads(Threads.MAX)
    public void testRouteKey(Blackhole bh) {
        int idx = ThreadLocalRandom.current().nextInt(keys.length);
        String key = keys[idx];
        String node = ring.routeKey(key);
        bh.consume(node);
    }
}
