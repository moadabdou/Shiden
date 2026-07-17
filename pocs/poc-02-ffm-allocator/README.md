# PoC 2: Off-Heap Arena & Slab Allocator

This Proof of Concept (PoC) implements a highly optimized, GC-free off-heap allocator system using Java 21's Foreign Function & Memory (FFM) API. It validates the memory model outlined in RFC-001 (Storage Engine) and RFC-008 (Memory Management).

---

## 📂 Planned File Structure

* `theory.md`: Foundational concepts, memory alignment details, and graduation questions.
* `mistakes.md`: Log of anticipated pitfalls and discovered engineering bugs.
* `pom.xml`: Maven build file configured for compilation with Java 21, preview features, and JMH.
* `src/main/java/shiden/poc/`:
  - `allocator/ArenaAllocator.java`: A thread-confined linear allocator that manages large off-heap pages.
  - `allocator/SlabAllocator.java`: A fixed-size block allocator optimized for metadata allocations (e.g., hash index buckets, queue nodes) with 64-byte alignment.
  - `allocator/MemoryLayouts.java`: Static definitions of record layouts and structured data mappings using FFM `MemoryLayout`.
  - `allocator/AllocatorSimulator.java`: A simulation comparison of heap-based allocations vs. off-heap allocations, demonstrating garbage collection impact.
  - `allocator/AllocatorBenchmark.java`: JMH benchmarks to measure raw allocation latency, read/write throughput, and GC pauses.

---

## 🎯 Technical Invariants to Prove

1. **Minimize Heap Allocations on Hot Paths**: The hot allocation path should avoid creating application-level heap objects and minimize FFM wrapper allocations.
2. **Sub-20 Nanosecond Allocations**: The Slab Allocator must execute $O(1)$ allocations in `< 20 ns` on uncontended hot paths.
3. **No External Memory Fragmentation**: Using fixed-size slab structures to eliminate external fragmentation entirely.
4. **Strict Memory Alignment**: Every allocation must align to 8-byte boundaries for values and 16-byte boundaries for structs to optimize CPU memory controller fetch cycles.

---

## 🎯 Ownership Model

* **One Arena per Partition Thread**: Each partition owns a single, thread-confined native `Arena`.
* **Isolated Arenas**: Arenas never cross ownership boundaries under standard execution.
* **Immutable Snapshot Transfers**: Hand-offs of state (e.g., to persistence/replication) are performed through immutable snapshots.
* **Detached Persistence Segments**: The background persistence loop receives detached segments, preventing concurrent modification.
* **Lifetime Bound**: The Arena's lifecycle is exactly bound to the Partition's execution lifetime.

---

## 🛡️ Scope Boundaries & Deferrals

To avoid early over-engineering, the scope of PoC 2 is strictly constrained:
* **In-Scope**: Proving Java FFM speed, showing GC pause elimination, verifying alignment compliance, and building a simple thread-local slab/arena allocator.
* **Out-of-Scope (Deferred to RFC-008 & Core Implementation)**: OS Huge Pages (`MAP_HUGETLB`), NUMA-aware pinning/affinity binding, lock-free allocators, and concurrent/multi-threaded slabs.

---

## 🚀 How to Run the Simulator (Planned)

The simulator executes two verification tests:
1. **GC Impact Comparison**: Allocates and discards 10 million short-lived records on the heap vs. in the off-heap slab allocator. Monitors GC pause times and heap growth.
2. **Alignment Verification**: Asserts that all allocated addresses align perfectly to CPU cache boundaries.

```bash
# Compile and package
mvn clean package

# Run the simulator class
mvn exec:java -Dexec.mainClass="shiden.poc.allocator.AllocatorSimulator"
```

---

## 📊 How to Run the JMH Benchmarks (Planned)

The benchmark compares the latency of:
* Standard JVM Object instantiation (`new MyObject()`) on the heap.
* Allocation from the custom FFM Slab Allocator.
* Read/write throughput of Java fields vs. low-level FFM `MemorySegment` access.

```bash
# Execute the generated fat jar
java -jar target/benchmarks.jar -wi 3 -i 5 -f 1
```

*Parameters explained:*
* `-wi 3`: 3 warmup iterations.
* `-i 5`: 5 measurement iterations.
* `-f 1`: Fork 1 separate JVM instance.

---

## ✍️ LinkedIn Playbook & Narrative

### Hook:
> *"Going GC-free in Java. Building a custom off-heap Arena Allocator using Foreign Function & Memory (FFM) API."*

### Outline:
- **The Problem**: JVM Garbage Collectors are excellent for general-purpose apps, but trace-and-sweep mechanics scale linearly with heap object count. Storing millions of keys on the heap causes Stop-The-World (STW) pauses that destroy tail latency (p99).
- **The Concept**: Segment memory into large virtual Pages from the OS. Sub-divide these pages manually into Slab blocks (fixed sizes: 64-byte aligned) using the Java 21 FFM API.
- **The Implementation**: An elegant visual comparison showing how `Arena` and `MemorySegment` can slice memory offsets without generating heap objects, using `VarHandle` for atomic pointer updates.
- **The Evidence**: A side-by-side comparison of heap object allocations vs. the custom allocator. Show a flat-lined JVM heap chart alongside JMH latency numbers proving off-heap allocations are deterministic and fast.

---

## 📝 Planned Experimental Results

*(To be populated with actual benchmark logs and heap utilization charts after the implementation phase is complete).*
