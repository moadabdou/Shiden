package shiden.poc;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class HashRingBenchmark {

    // TODO: Define @Param values for vnodes (e.g. 10, 100, 500)
    // TODO: Define @Param values for hashType (e.g. MURMUR3, JDK_HASHCODE)

    @Setup(Level.Trial)
    public void setup() {
        // TODO: Initialize your ConsistentHashRing and pre-generate keys
    }

    @Benchmark
    @Threads(Threads.MAX)
    public void testRouteKey(Blackhole bh) {
        // TODO: Route random keys and consume the result to prevent dead-code elimination.
        // Hint: Avoid thread contention on shared random seeds!
    }
}
