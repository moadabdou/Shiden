# Engineering Notebook: Mistakes, False Assumptions, & Pitfalls

This document logs the design flaws, incorrect assumptions, and performance bottlenecks discovered during the design, implementation, and benchmarking of the Thread-per-Core MPSC Mailbox Dispatcher and NUMA Affinity subsystem.

---

## 🚫 Anticipated Pitfalls to Validate

### 1. Cache Line False Sharing on Sequence Pointers
* **False Assumption**: Placing `producerHead` and `consumerTail` variables in adjacent class fields is sufficient for memory safety and latency.
* **The Reality**: Adjacent variables occupy the same 64-byte or 128-byte CPU L1 cache line. Multi-threaded updates to `producerHead` by Netty I/O threads invalidate `consumerTail` in the worker thread's L1 cache, causing severe MESI cache invalidation ping-ponging ($10\times$ throughput drop).
* **Investigation Plan**: Apply explicit 128-byte dummy long padding (`long p01, p02, ... p15`) around producer and consumer fields and measure enqueue latency under 16 concurrent threads.

### 2. Heap Object Allocation inside High-Frequency Mailbox Loops
* **False Assumption**: Instantiating a lightweight `Command` object or lambda Runnable per incoming request in the mailbox loop is cheap in Java 21.
* **The Reality**: At 50,000,000 requests/sec, allocating wrapper objects on the JVM heap creates gigabytes of garbage per second, triggering heavy GC tracing and defeating Shiden's zero-GC architecture.
* **Investigation Plan**: Enqueue primitive command parameter tuples (`long key, int opType, int pageId, short slotId`) into off-heap ringbuffer slots to achieve 0 Bytes heap allocation.

### 3. Idle Consumer Core Spinning vs. Thread Parking
* **False Assumption**: When the mailbox is empty, the worker thread should continuously spin in a `while(true)` loop for immediate responsiveness.
* **The Reality**: Infinite un-backed-off spinning consumes 100% of a physical core's execution resources, generating heat and starving adjacent hyper-threads sharing the physical execution pipeline.
* **Investigation Plan**: Benchmark a 3-phase idle backoff strategy (Spin 1,000 iterations $\rightarrow$ `Thread.onSpinWait()` $\rightarrow$ Park) and measure idle CPU overhead vs. wake-up latency.

### 4. Unbounded Maintenance Task Traversal
* **False Assumption**: Allowing background maintenance callbacks (table rehashing, LFU eviction sweeps) to run until completion in a single iteration ensures data structure readiness.
* **The Reality**: Running unbounded maintenance in a single-threaded loop blocks incoming user commands, exploding p99.9 tail latency from microseconds to tens of milliseconds.
* **Investigation Plan**: Restrict all background maintenance operations to small, bounded tax units (e.g. migrating max 4 entries per loop) inside a 50µs cooperative time-sliced execution budget.

### 5. Cross-Socket NUMA Interconnect Misalignment
* **False Assumption**: Panning worker threads to any available CPU core without checking socket topology is fine.
* **The Reality**: Binding worker thread $A$ to Socket 0 while allocating its off-heap native memory segment from Socket 1 forces every read across the Ultra Path Interconnect (UPI / Infinity Fabric), adding a $2.5\times$ latency penalty ($\sim 150\text{ ns}$).
* **Investigation Plan**: Use `numactl --hardware` and OpenHFT Thread Affinity to pin worker threads and allocate native memory strictly on the same local NUMA socket node.

---

## 📝 Discovered Mistakes & Lessons Learned

*(To be populated as empirical code experiments are executed during PoC-04 development)*
