# PoC 1: Consistent Hashing Ring

This Proof of Concept (PoC) implements a highly optimized, thread-safe consistent hashing ring using Java 21.

---

## 📂 File Structure

* `theory.md`: Prerequisites, academic reference material, and graduation questions.
* `mistakes.md`: Log of code issues, benchmarking pitfalls, and layout problems discovered.
* `pom.xml`: Maven build file configured for compilation with Java 21, preview features, and JMH.
* `src/main/java/shiden/poc/`:
  - `hash/Murmur3.java`: A fast, zero-dependency implementation of the 32-bit Murmur3 algorithm.
  - `ring/ConsistentHashRing.java`: The core consistent hash ring.
  - `ConsistentHashRingSimulator.java`: A simulation runner measuring key distribution variance and key migration percentages during scale-up.
  - `HashRingBenchmark.java`: The JMH micro-benchmark class.

---

## 🚀 How to Run the Simulator

The simulator executes two verification tests:
1. **Load Distribution Variance**: Measures the uniformity of key distributions comparing JDK `hashCode()` and `Murmur3` as the number of virtual nodes ($V$) scales.
2. **Scale-Up Key Migration**: Verifies that adding a new node to a 5-node cluster only moves $\approx 16\%$ of keys (compared to $\approx 83\%$ in modulo hashing).

To compile and run the simulator:

```bash
# Compile and package
mvn clean package

# Run the simulator class
mvn exec:java -Dexec.mainClass="shiden.poc.ConsistentHashRingSimulator"
```

---

## 📊 How to Run the JMH Benchmarks

The benchmark tests the lookup latency of `routeKey` under varying thread counts and ring configurations.

To run the JMH benchmark:

```bash
# Execute the generated fat jar
java -jar target/benchmarks.jar -wi 3 -i 5 -f 1
```

*Parameters explained:*
* `-wi 3`: 3 warmup iterations.
* `-i 5`: 5 measurement iterations.
* `-f 1`: Fork 1 separate JVM instance.

---

## 📝 Experimental Results

### 1. Load Distribution Simulator Outputs
We mapped 100,000 keys to a 5-node cluster.

| Hash Type | VNodes/Node | Coeff. of Variation (%) | Raw Node Distribution |
| :--- | :---: | :---: | :--- |
| **MURMUR3** | 1 | 67.46 | {node-1=26433, node-2=36639, node-3=5572, node-4=28837, node-5=2519} |
| **MURMUR3** | 10 | 18.55 | {node-1=19170, node-2=20241, node-3=13570, node-4=24538, node-5=22481} |
| **MURMUR3** | 50 | 17.60 | {node-1=21297, node-2=25215, node-3=20059, node-4=19107, node-5=14322} |
| **MURMUR3** | 100 | 6.11 | {node-1=18616, node-2=20401, node-3=21891, node-4=18710, node-5=20382} |
| **MURMUR3** | 250 | 3.17 | {node-1=21074, node-2=19629, node-3=20360, node-4=19582, node-5=19355} |
| **MURMUR3** | 500 | 2.98 | {node-1=21063, node-2=19328, node-3=19593, node-4=20118, node-5=19898} |
| **JDK_HASHCODE** | 1 to 500 | 200.00 | {node-1=100000, node-2=0, node-3=0, node-4=0, node-5=0} |
| **MURMUR3 (SLOTS Q=1024)** | 150 | 3.36 | {node-1=20258, node-2=19389, node-3=21134, node-4=19950, node-5=19269} |
| **MURMUR3 (SLOTS Q=16384)** | 150 | 3.80 | {node-1=20896, node-2=19470, node-3=20957, node-4=19404, node-5=19273} |
| **JDK_HASHCODE (SLOTS Q=1024)** | 150 | 200.00 | {node-1=100000, node-2=0, node-3=0, node-4=0, node-5=0} |

* **Insight 1 (The VNode Effect)**: As the number of virtual nodes per physical node ($V$) scales from 1 to 500, the Coefficient of Variation (CV) drops from 67.46% to 2.98% under Murmur3. This empirically proves that scattering virtual nodes around the ring effectively smooths out random interval sizes, establishing a highly balanced key distribution.
* **Insight 2 (The JDK hashCode Disaster)**: Standard JDK `hashCode()` is completely unsuitable for consistent hashing rings. Across all virtual node sizes, it displays a 200% coefficient of variation because all 100,000 keys map to `node-1`. Diagnostic validation confirms this is not an implementation bug, but a mathematical collapse of polynomial rolling hashes. Because string patterns of virtual nodes like `"node-X#Y"` are nearly identical, their hashcodes cluster tightly in a narrow range (`1,121,848,851` to `1,121,852,704`), representing only 0.00009% of the integer space. Consequently, nearly 100% of routed key hashes fall outside this range and wrap around to index 0 (which is assigned to `node-1`), collapsing the distribution.

### 2. Scale-Up Key Migration (5 Nodes to 6 Nodes)
* **Naive Modulo Hashing**: 83.49% of keys changed their mapped node. This aligns with the theoretical migration fraction of $N/(N+1) = 5/6 \approx 83.33\%$.
* **Consistent Hashing ($V=150$)**: 14.56% of keys migrated to the new node, which is close to the optimal theoretical minimum of $1/(N+1) = 1/6 \approx 16.67\%$.
* **Slot-based Hashing ($Q=1024, V=150$)**: 13.34% of keys migrated to the new node.
* **Analysis**: Adding a node under modulo hashing forces a near-complete re-partitioning of the dataset. For Shiden's partition-centric architecture, slot ownership diffs are dramatically cheaper than key-level remapping. Both Consistent Hashing (Strategy A) and Slot-based Hashing (Strategy B) restrict key migration solely to the segments claimed by the new virtual nodes, preventing cache-miss storms or database thrashing.

### 3. JMH Micro-benchmark Results
Tested lookup latency of `routeKey` under maximum concurrent load:

```
Benchmark                         (hashType)  (routingMode)  (vnodes)  Mode  Cnt    Score    Error  Units
HashRingBenchmark.testRouteKey       MURMUR3     CONSISTENT        10  avgt    5   98.386 ± 49.135  ns/op
HashRingBenchmark.testRouteKey       MURMUR3     CONSISTENT       100  avgt    5  141.334 ± 10.484  ns/op
HashRingBenchmark.testRouteKey       MURMUR3     CONSISTENT       500  avgt    5  164.129 ± 71.223  ns/op
HashRingBenchmark.testRouteKey       MURMUR3          SLOTS        10  avgt    5   58.071 ±  4.358  ns/op
HashRingBenchmark.testRouteKey       MURMUR3          SLOTS       100  avgt    5   71.059 ± 41.063  ns/op
HashRingBenchmark.testRouteKey       MURMUR3          SLOTS       500  avgt    5   61.193 ± 18.181  ns/op
HashRingBenchmark.testRouteKey  JDK_HASHCODE     CONSISTENT        10  avgt    5   28.687 ±  0.820  ns/op
HashRingBenchmark.testRouteKey  JDK_HASHCODE     CONSISTENT       100  avgt    5   54.068 ± 10.275  ns/op
HashRingBenchmark.testRouteKey  JDK_HASHCODE     CONSISTENT       500  avgt    5   53.517 ±  2.191  ns/op
HashRingBenchmark.testRouteKey  JDK_HASHCODE          SLOTS        10  avgt    5   21.007 ±  3.391  ns/op
HashRingBenchmark.testRouteKey  JDK_HASHCODE          SLOTS       100  avgt    5   20.634 ±  3.862  ns/op
HashRingBenchmark.testRouteKey  JDK_HASHCODE          SLOTS       500  avgt    5   20.444 ±  2.008  ns/op
```

* **Insight 3 (Lookup Complexity)**: Lookup latency increases as $V$ grows (from ~98 ns at $V=10$ to ~164 ns at $V=500$ for Murmur3 under `CONSISTENT` mode). This confirms the $O(\log(N \cdot V))$ search cost on our array-based ring representation.
* **Insight 4 (The Caching Trap)**: `JDK_HASHCODE` appears faster than Murmur3 because the JVM's `String.hashCode()` caches its calculated hash inside the `String` object itself after its first access. Subsequent lookups simply read a cached `int` field ($O(1)$) instead of performing actual calculations, giving a false impression of performance.
* **Insight 5 (The Slots Decoupling / $O(1)$ Constant Time)**: Routing keys using a slot table (`SLOTS` mode) decouples lookup latency from the virtual node count $V$. Under `MURMUR3` with `SLOTS`, routing latency is constant around ~58-71 ns whether $V=10$ or $V=500$. This is because the ring is only traversed during cluster configuration updates (write-path), while runtime lookups (read-path) utilize a fast-path modulo operation followed by direct array indexing, achieving $O(1)$ complexity.
* **Insight 6 (Production & Replication Benefits)**: Beyond the lookup speedup, Strategy B (Slots) provides a critical production benefit for Shiden: partition boundaries never change. When nodes join or leave, slots migrate as self-contained partition snapshots or storage segments without requiring individual key scans. Merkle trees can be built per partition, preventing the need to recalculate them during cluster re-balancing.
* **Production Takeaway**: To build a high-performance system like Shiden, we cannot rely on JDK `String.hashCode()` due to its poor distribution. To bypass the String caching trap and avoid garbage collection overhead, we should pass raw byte arrays or off-heap `MemorySegment` buffers directly to `Murmur3.hash32` in the network layer, achieving both uniform key routing and zero heap allocations.

### 4. Migration Routing Overhead Simulation (Strategy A vs. Strategy B)
We simulated the time required to identify which resources must migrate when the cluster scales up from 5 to 6 nodes (re-routing 100,000 keys).

| Hashing Strategy | Evaluation Scope | Units Checked | Time to Identify (ms) | Disk/Memory Access Pattern |
| :--- | :--- | :---: | :---: | :--- |
| **Strategy A** (Consistent Hashing) | Key-by-Key scan | 100,000 keys | 20.29 ms | Random disk seeks / database index lookup |
| **Strategy B** (Slot Hashing) | Slot mapping diff | 1,024 slots | 0.41 ms | Contiguous partition file streaming (e.g. SSTables) |

* **Insight 7 (CPU Scan Elimination)**: Strategy B performs **49.0x faster** in identifying migrating resources on the CPU. Instead of computing hashes and querying the hash ring for all 100,000 keys, Strategy B simply compares the ownership tables of the 1,024 slots. 
* **Insight 8 (I/O Bottleneck Avoidance)**: Under Strategy A, resolving migrations requires key-level remapping and traversing the ring. Under Strategy B, keys are partitioned into fixed, self-contained snapshots or segments. When slot ownership changes, Shiden can stream the entire segment sequentially over the network, maximizing network throughput and eliminating key-level traversal overhead.
