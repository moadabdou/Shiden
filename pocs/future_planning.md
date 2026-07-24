# Future PoC Engineering Roadmap

This document outlines the strategic roadmap for PoCs following **PoC-01** (Consistent Hashing) and **PoC-02** (Off-Heap Allocator). Each PoC isolates, challenges, and empirically benchmarks core architectural hypotheses established in Shiden's RFCs.

---

## 🎯 Completed Milestone: PoC-03 (Off-Heap Hash Index)
* **Status**: **COMPLETED (PASSED)**
* **Target RFC**: RFC-002 (Off-Heap Hash Index Engine)
* **Graduation Results**: Achieved 3.21 ns hot lookups, 9.56 ns batch prefetching, 23.34 ns 1M-scale lookup via L2 sharding (4.8x speedup), and 1.56 µs p99.9 tail latency during active rehashing with 0 JVM Heap allocations.

---

## 🎯 Active Milestone: PoC-04 (Thread-per-Core MPSC Mailbox Dispatcher & NUMA Affinity)
* **Status**: **IN PROGRESS**
* **Target RFC**: RFC-004 (Partition Architecture & Execution Model), RFC-003 (Networking)
* **Core Question**: Can a thread-per-core MPSC ringbuffer mailbox dispatcher maintain sub-100ns queue enqueue latency and zero lock contention across multi-producer thread workloads while enforcing CPU core pinning?

---

## 🔮 Future PoC Candidates (Post-PoC-03 Roadmap)

### 1. PoC-04: Thread-per-Core MPSC Mailbox Dispatcher & NUMA Affinity
* **Target RFC**: RFC-004 (Partition Architecture & Execution Model), RFC-003 (Networking)
* **Core Hypothesis**: Netty I/O threads enqueuing commands into worker thread MPSC mailboxes (JCTools / Agrona RingBuffer) will maintain $< 100\text{ ns}$ queue drop latency under multi-producer CPU interconnect contention.
* **Experiments to Run**:
  - Benchmark MPSC queue enqueue latency under 1, 4, 8, and 16 concurrent producer event loops.
  - Measure cross-socket NUMA interconnect latency penalties vs. native CPU core pinning using OpenHFT Java Thread Affinity.
  - Test 50µs time-sliced cooperative multitasking between command execution and memory maintenance cycles.

---

### 2. PoC-05: Slotted Storage Page Engine & In-Page Compaction
* **Target RFC**: RFC-001 (Off-Heap Engine & Storage Page Format)
* **Core Hypothesis**: 4KB off-heap storage pages with bottom-up payload packing and top-down slot directories will maintain $< 50\text{ ns}$ record reads while defragmenting deleted space via in-page compaction without L1 cache thrashing.
* **Experiments to Run**:
  - Benchmark variable-length record packing (8B to 2KB payloads) into 4KB and 16KB off-heap page segments.
  - Test tombstone deletion and measure in-page defragmentation (`MemorySegment.copy`) overhead under high update/delete churn.
  - Compare page overhead across 4KB, 16KB, and 64KB page extents.

---

### 3. PoC-06: Write-Ahead Logging (WAL) & Direct I/O Persistence
* **Target RFC**: RFC-007 (Persistence Architecture)
* **Core Hypothesis**: Asynchronous ring-buffered WAL flushing using Linux Direct I/O (`O_DIRECT`) or `io_uring` will sustain 100,000 writes/sec with zero JVM GC or kernel page cache buffer pollution.
* **Experiments to Run**:
  - Compare Java 21 FFM Direct File Access vs. `FileChannel.force(false)` vs. `io_uring` native bindings.
  - Measure group-commit batching latency across NVMe SSD drives under high write pressure.

---

### 4. PoC-07: Multi-Raft Partition Consensus & Async Replication
* **Target RFC**: RFC-006 (Replication & High Availability)
* **Core Hypothesis**: Single-threaded Multi-Raft consensus pipeline per worker thread will maintain active-active replica synchronization with $< 1\text{ ms}$ failover latency.
* **Experiments to Run**:
  - Benchmark Raft log replication throughput across partitioned off-heap ringbuffers.
  - Measure leader election failover latencies under network partition fault injection.
