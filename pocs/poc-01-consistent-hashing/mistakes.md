# Engineering Notebook: Mistakes, False Assumptions, & Pitfalls

This document logs the design flaws, incorrect assumptions, and performance bottlenecks discovered during the implementation and benchmarking of the Consistent Hashing Ring.

---

## 🚫 Anticipated Pitfalls to Validate

### 1. The JVM `Object.hashCode()` Trap
* **False Assumption**: Standard `Object.hashCode()` is sufficient for placing keys and nodes on the ring.
* **The Reality**: `hashCode()` has very low entropy, poor distribution, and can vary across JVM instances or runs. Relying on it leads to severe partition imbalance (hotspots).
* **Investigation Plan**: We will test a standard hash ring using `hashCode()` vs. a ring using Murmur3 to demonstrate the variance in distribution.

### 2. Negative Hashes & Ring Wrap-Around
* **False Assumption**: Ring positions can be calculated using raw signed 32-bit integers (`int`) without addressing sign extensions or negative values.
* **The Reality**: Standard hash functions can return negative integers. If we use a simple binary search or `TreeMap.tailMap()` on signed integers, we risk misinterpreting the wrap-around point (e.g., treating positive hashes as "after" negative hashes in a way that breaks circular lookup logic).
* **Investigation Plan**: Implement a proper unsigned conversion or map integers into the 64-bit positive space (`long`) to guarantee correct circle routing.

### 3. Over-Optimizing Virtual Node Count ($V$)
* **False Assumption**: The more virtual nodes, the better. Let's use $V = 10,000$ per physical node.
* **The Reality**: Increasing $V$ increases the memory footprint of the ring metadata and degrades lookup times. If the ring is stored in a `TreeMap`, lookup complexity is $O(\log(N \cdot V))$. If $N=10$ and $V=10,000$, we have $100,000$ entries. Lookups require binary searches across a large structure, degrading latency.
* **Investigation Plan**: Run benchmarks measuring lookup throughput and latencies across $V \in \{1, 10, 50, 100, 250, 500, 1000\}$ to find the optimal trade-off point between load distribution balance and lookup speed.

### 4. Concurrent Modification during Node Failover
* **False Assumption**: Reading from the ring is safe while nodes are being added or removed.
* **The Reality**: In a multi-threaded system, if one thread reads from a shared, mutable `TreeMap` while another updates it (e.g., node joining), it will throw a `ConcurrentModificationException` or return incorrect nodes.
* **Investigation Plan**: Test concurrency strategies (Copy-On-Write structures vs. Read-Write locks) to determine the best throughput-vs-safety trade-off.

---

## 📝 Discovered Mistakes (Log)

* **Mistake #1: Multi-Threaded Benchmark Polluted by `java.util.Random`**
  - *Symptom*: When testing lookups with multiple threads (`Threads.MAX`), latency increased to hundreds of nanoseconds.
  - *Cause*: A shared `java.util.Random` instance was used to select keys. The `nextInt()` call internally modifies a shared atomic seed, causing threads to block on CAS (Compare-And-Swap) contention. We were benchmarking CAS contention on the seed rather than the hash ring lookup.
  - *Fix*: Switched key selection to use `ThreadLocalRandom.current().nextInt(keys.length)`.
  - *Lesson*: Never share random number generators across multiple threads in JMH benchmarks.

* **Mistake #2: Unsigned Ring mapping with Signed Arrays**
  - *Symptom*: Initial design of circle traversal was confusing due to negative values from Murmur3 and JDK `hashCode()`.
  - *Cause*: In a circular space, we map from 0 to $2^{32}-1$. However, Java lacks native unsigned 32-bit integers, meaning our sorted array goes from the most negative integer to the most positive integer.
  - *Fix*: We kept the signed `int[]` representation because `Arrays.binarySearch` operates on sorted signed arrays. By mapping values that exceed the largest signed integer in the array to index `0` (the most negative element), we logically preserve the circle wrap-around behavior without needing to do expensive transformations to unsigned types on every routing operation.

* **Mistake #3: Heavy GC Allocations in Murmur3 Lookup (String to Byte Array)**
  - *Symptom*: JMH benchmarks showed Murmur3 was ~4x slower than JDK `hashCode()`.
  - *Cause*: `String.hashCode()` caches its hash inside the `String` object. Once computed, subsequent lookups are $O(1)$ reads of a cached field. Meanwhile, `Murmur3.hash32(String)` calls `text.getBytes(StandardCharsets.UTF_8)` on every lookup, allocating a new `byte[]` array on the heap. This causes huge allocation pressure and garbage collection overhead in hot paths.
  - *Fix*: In a production database like Shiden, keys should never be processed as Java `String` objects on the hot path. They should be parsed directly from network socket buffers into raw byte arrays or off-heap `MemorySegment` objects and passed directly to the hash ring. For the purposes of this PoC, we benchmarked the lookup logic, but we must highlight that routing on raw `byte[]` or `MemorySegment` is the required path for zero-allocation performance.


