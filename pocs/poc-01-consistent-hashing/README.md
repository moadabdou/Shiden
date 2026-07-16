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
| **MURMUR3** | 1 | | |
| **MURMUR3** | 10 | | |
| **MURMUR3** | 50 | | |
| **MURMUR3** | 100 | | |
| **MURMUR3** | 250 | | |
| **MURMUR3** | 500 | | |
| **JDK_HASHCODE** | 1 to 500 | | |

* **Insight 1 (The VNode Effect)**: 
* **Insight 2 (The JDK hashCode Disaster)**: 

### 2. Scale-Up Key Migration (5 Nodes to 6 Nodes)
* **Naive Modulo Hashing**: 
* **Consistent Hashing ($V=150$)**: 
* **Analysis**: 

### 3. JMH Micro-benchmark Results
Tested lookup latency of `routeKey` under maximum concurrent load:

```
Benchmark                         (hashType)  (vnodes)  Mode  Cnt    Score     Error  Units
HashRingBenchmark.testRouteKey       MURMUR3        10  avgt    5
HashRingBenchmark.testRouteKey       MURMUR3       100  avgt    5
HashRingBenchmark.testRouteKey       MURMUR3       500  avgt    5
HashRingBenchmark.testRouteKey  JDK_HASHCODE        10  avgt    5
HashRingBenchmark.testRouteKey  JDK_HASHCODE       100  avgt    5
HashRingBenchmark.testRouteKey  JDK_HASHCODE       500  avgt    5
```

* **Insight 3 (Lookup Complexity)**: 
* **Insight 4 (The Caching Trap)**: 
* **Production Takeaway**: 
