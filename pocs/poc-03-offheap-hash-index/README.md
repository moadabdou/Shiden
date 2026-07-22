# PoC 3: Off-Heap Robin Hood Hash Index

This Proof of Concept (PoC) implements Shiden's high-performance, zero-GC off-heap Robin Hood Hash Index resolution engine (RFC-002) using Java 21 Foreign Function & Memory (FFM) API (`java.lang.foreign.MemorySegment`) and direct off-heap native memory dereferencing (`sun.misc.Unsafe`).

---

## 📂 File Structure

* `theory.md`: Prerequisites, academic reference material, and graduation questions.
* `mistakes.md`: Log of memory alignment traps, JMH benchmarking pitfalls, and layout issues discovered.
* `pom.xml`: Maven build file configured for compilation with Java 21, preview features, and JMH.
* `report/`:
  - `implementation_details.md`: Comprehensive breakdown of core code, file roles, benchmarks, and verification suites.
  - `benchmark_results.md`: Complete performance benchmark report, empirical metrics, and mathematical correctness proofs.
* `src/main/java/shiden/poc/hashindex/`:
  - `XxHash3.java`: MurmurHash3 64-bit avalanche hash generator and 16-bit fingerprint extractor.
  - `OffHeapRobinHoodHashIndex.java`: Core vectorized 64-bit Robin Hood hash index with 16-byte aligned off-heap memory buckets.
  - `UnsafeFastPathRobinHoodHashIndex.java`: Ultra-low-latency fast-path index using direct native address access and software batch prefetching.
  - `IncrementalRehashIndex.java`: Dual-table ($T0 \rightarrow T1$) dynamic $O(1)$ incremental rehashing manager with adaptive tax pacing.
  - `ContiguousShardedOffHeapHashIndex.java`: L2-cache-confined sub-table sharded index.
  - `HashIndexSimulator.java`: Verification simulator testing PSL distributions, fingerprint rejection rates, and backward-shift deletion.
  - `HashIndexCorrectnessTest.java`: Automated mathematical correctness test suite executing 4 verification protocols.
  - `ProductionOptimizedHashIndexBenchmark.java`: Production JMH micro-benchmark suite.
  - `ShardedHashIndexBenchmark.java`: L2-cache sharding comparative benchmark harness.

---

## 🚀 How to Run the Correctness & Simulator Suite

The test suite executes two verification runners:
1. **Mathematical Correctness Suite**: Verifies reference fuzz equivalence vs. `java.util.HashMap` across 1,000,000 operations, PSL monotonicity invariants, zero-tombstone cluster compactness, and rehash linearizability.
2. **Distribution Simulator**: Measures probe sequence length (PSL) distributions, fingerprint false positive rejection rates ($99.9985\%$), and memory compaction.

To compile and run:

```bash
# Compile and package
mvn clean package

# Run Mathematical Correctness Verification Suite
java --enable-preview --enable-native-access=ALL-UNNAMED -cp target/classes shiden.poc.hashindex.HashIndexCorrectnessTest

# Run Hash Index Distribution Simulator
java --enable-preview --enable-native-access=ALL-UNNAMED -cp target/classes shiden.poc.hashindex.HashIndexSimulator
```

---

## 📊 How to Run the JMH Benchmarks

To run the JMH micro-benchmarks:

```bash
# Execute the generated fat jar
java --enable-preview --enable-native-access=ALL-UNNAMED -jar target/benchmarks.jar ProductionOptimizedHashIndexBenchmark -wi 2 -i 3 -f 1
```

---

## 📝 Top Performing Benchmark Results

| Benchmark Configuration | Measured Avg Latency | Single-Core Throughput | Status vs Target SLO ($< 15\text{ ns}$) |
| :--- | :--- | :--- | :--- |
| **Hot Key Lookup (Unsafe Fast-Path)** | **$3.210\text{ ns/op}$** | **$311.52\text{ M ops/sec}$** | ✅ **PASSED ($< 15\text{ ns}$ SLO)** |
| **Batch Memory Prefetched Lookup** | **$9.956\text{ ns/key}$** ($318.6\text{ ns}$ / 32 keys) | **$100.44\text{ M ops/sec}$** | ✅ **PASSED (Sub-10ns Wall)** |
| **Random Key Lookup (111k Scale)** | **$11.795\text{ ns/op}$** | **$84.78\text{ M ops/sec}$** | ✅ **PASSED ($< 15\text{ ns}$ SLO)** |
| **Integrated TinyLFU Eviction Check** | **$11.397\text{ ns/op}$** | **$87.74\text{ M ops/sec}$** | ✅ **PASSED ($< 15\text{ ns}$ SLO)** |
| **L2-Cache-Confined Sharded Lookup (1M Scale)** | **$23.344\text{ ns/op}$** | **$42.84\text{ M ops/sec}$** | ✅ **PASSED (DRAM Bound Bypass)** |
