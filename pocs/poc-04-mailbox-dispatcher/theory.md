# Theory Checkpoint: Thread-per-Core MPSC Mailbox Dispatcher & NUMA Affinity

To support Shiden's predictable sub-millisecond data grid latencies, command dispatching from multi-threaded I/O network event loops to database partitions must execute in under 100 nanoseconds without incurring JVM heap allocations, thread lock contention, or cross-socket NUMA memory bottlenecks.

PoC-04 validates the design of Shiden's **Partition Architecture & Execution Model (RFC-004)** and **Client Networking Layer (RFC-003)**.

---

## 🧠 Core Concepts

### 1. The Multi-Threaded Locking Problem (Standard Application Server Defect)
Standard Java multi-threaded application servers rely on shared-memory concurrency where worker threads contend for shared data structures using locks (`ReentrantLock`, `ConcurrentHashMap`) or atomic primitives (`CAS`).
* **Lock Contention & Preemption**: Threads frequently block and undergo kernel context switches ($1\text{--}5\,\mu\text{s}$ per switch).
* **Cache Line Bouncing**: Multiple CPU cores updating shared memory locations cause continuous MESI cache coherence invalidations across L1/L2 caches.
* **Non-Deterministic Tail Latency**: A single thread preempted while holding a lock spikes p99.9 tail latency for all competing threads.

### 2. Thread-per-Core (TPC) & Partition Ownership Model
To eliminate lock contention completely, Shiden adopts a **Thread-per-Core (TPC) Shared-Nothing** architecture inspired by **LMAX Disruptor** and **Seastar**:
* **Single-Threaded Worker Core**: Each physical CPU core runs exactly one dedicated Worker Thread pinned via CPU affinity (`sched_setaffinity`).
* **Logical Partition Mapping**: Multiple logical database partitions (e.g., 64 partitions out of 1024 total) are mapped strictly to a single Worker Thread.
* **Zero Synchronization Inside Partition**: Data structures inside a partition (Hash Index RFC-002, Storage Pages RFC-001) are 100% thread-confined—**zero locks, zero CAS instructions, zero atomic memory barriers**.

### 3. Multi-Producer Single-Consumer (MPSC) Worker-Thread Mailbox
Network I/O threads (Netty Event Loops) do not mutate partition state directly. Instead, they route and enqueue incoming commands into a bounded **Worker-Thread MPSC Mailbox**:

```text
Netty Producer EventLoop 1 ──┐
Netty Producer EventLoop 2 ──┼──► [ MPSC Worker Mailbox ] ──► (JCTools MpscArrayQueue / Agrona)
Netty Producer EventLoop N ──┘            │
                                          ▼
Worker Thread Consumer (Pinned Core) ◄────┘
   ├── Partition 0  (Thread-Confined Engine)
   ├── Partition 1  (Thread-Confined Engine)
   └── Partition 63 (Thread-Confined Engine)
```

* **Mailbox Granularity**: Rather than maintaining 1,024 separate queues (which would force worker threads to thrash polling 64 empty queues per cycle), Shiden uses **One Mailbox per Worker Thread** (RFC-004 Section 5.1).
* **Standardized Queue Infrastructure**: Per **RFC-003 Section 7.1**, to guarantee battle-tested correctness and prevent subtle lock-free concurrency bugs, Shiden utilizes production MPSC queues (**JCTools `MpscArrayQueue`** or **Agrona RingBuffers**). For custom FFM off-heap structures, memory ordering follows `VarHandle` standards established in **PoC-02 (FFM Allocator)**.
* **Deterministic Command Routing**:
  1. $\text{Hash} = \text{xxHash3-64}(\text{key})$
  2. $\text{PartitionID} = \text{Hash} \pmod{1024}$
  3. $\text{TargetWorker} = \text{PartitionID} \pmod{16}$
  4. Producer enqueues payload directly into `WorkerThread[TargetWorker]`'s Mailbox.

### 4. Zero-Copy `ByteBuf` Lifecycle & Adaptive Command Context Pipeline
Rather than unconditionally copying key/value payloads or passing static primitive tuples, the messaging pipeline maintains an **Adaptive NUMA-Aware Lifecycle** (**RFC-003 Section 5.2**):

1. **Decode & Retain**: Netty EventLoops parse binary protocol frame headers and slice off-heap network buffers.
2. **Worker & NUMA Resolution**: Using a static, immutable `WorkerDirectory` (`WorkerID -> NUMA_Node`), the EventLoop evaluates whether the target Worker Thread resides on the same NUMA node.
3. **Enqueue Context**: The EventLoop constructs a lightweight `CommandContext` carrying `(RequestID, OpCode, ChannelContext, ByteBuf, OriginatingNumaNode)` and enqueues it to the target Worker Thread's Mailbox.
4. **Adaptive Execution & Release**: 
   - **Intra-NUMA**: The pinned Worker Thread drains `CommandContext` zero-copy, executes request decoding, and invokes `ByteBuf.release()` when finished.
   - **Inter-NUMA**: Depending on payload size relative to an adaptive threshold, the Worker either processes the retained `ByteBuf` directly or eagerly copies the bytes into a **worker-local transient staging buffer**, releasing the remote `ByteBuf` immediately to minimize UPI interconnect reads during processing.
5. **Response Handoff**: The Worker Thread returns a response structure back to the originating EventLoop's MPSC Response Queue for batch flushing.

### 5. NUMA Interconnect & Memory Affinity Dynamics
Modern multi-socket server hardware features Non-Uniform Memory Access (NUMA):
* **Local NUMA Node Access**: CPU cores accessing local socket RAM ($\sim 1\times$ baseline memory latency).
* **Remote NUMA Node Access**: CPU cores accessing remote socket RAM across Ultra Path Interconnect (UPI / Infinity Fabric) ($\sim 2\times\text{--}3\times$ latency penalty + interconnect bus contention).

> **Core System Philosophy**: *The objective of Shiden's cross-socket adaptive policy is not strictly to eliminate memory copies, but to minimize total end-to-end request completion time (p99 tail latency) under NUMA constraints.*

#### Architecture & Placement Strategy
1. **Thread Affinity & First-Touch Placement**: EventLoops and Worker Threads are deployed under strict CPU core pinning (`sched_setaffinity`). Netty off-heap pooled allocations naturally follow OS **first-touch placement** on the local NUMA node. Platform-specific mechanisms (`numactl`, `mbind()`, or FFM custom allocators) are leveraged where available.
2. **Static `WorkerDirectory`**: At startup, an immutable topology map assigns each `WorkerID` and `PartitionID` to a specific physical core and NUMA socket ID, allowing EventLoops to perform $O(1)$ NUMA locality checks without locks.
3. **Adaptive Cross-Socket Handoff (PoC-04 Hypothesis)**:
   - **Intra-NUMA (`EventLoop.NUMA == Worker.NUMA`)**: Always **Zero-Copy** (`ByteBuf.retain()`). Memory is local to the core socket.
   - **Inter-NUMA (`EventLoop.NUMA != Worker.NUMA`)**: 
     - *Payloads $\le$ Threshold (Hypothesis: 512 B)*: Processed **Zero-Copy**. The latency of 1–8 remote cache line fetches is smaller than allocating and copying to a local buffer.
     - *Payloads $>$ Threshold*: Worker eagerly copies payload bytes into a **worker-local transient staging buffer** (decoupled from storage engine arenas) and calls `ByteBuf.release()` immediately. Subsequent protocol parsing, validation, CRC checking, and indexing run 100% in local RAM.

#### PoC-04 Empirical Threshold Validation
The optimal crossover threshold is not hardcoded as a fixed constant; it is an empirical property of the underlying hardware (e.g., DDR4 vs DDR5, PCIe / UPI generation). PoC-04 explicitly benchmarks payload sizes from $64\text{ B}$ to $8\text{ KB}$ under cross-socket contention to measure the exact latency crossover point.

### 6. Cooperative Time-Sliced Multitasking
Because a partition worker thread is single-threaded, it must execute incoming user requests while periodically running background maintenance (incremental rehashing, eviction sweeps, WAL flushes).

We enforce a **50µs Cooperative Time-Sliced Loop** (RFC-004 Section 6):
1. Drain up to $N$ commands (e.g., 128 commands) from the Worker Thread's MPSC Mailbox (max 50µs execution budget).
2. For each command, inspect `PartitionID` and perform Hash Index lookup ([PoC-03](file:///home/moadabdou/coding/serious_projects/Shiden/pocs/poc-03-offheap-hash-index/theory.md)) and Page write ([PoC-02](file:///home/moadabdou/coding/serious_projects/Shiden/pocs/poc-02-ffm-allocator/theory.md)).
3. Execute 1 step of active background maintenance (`IncrementalRehashIndex.stepRehash()` per PoC-03).
4. Execute 1 step of Eviction Index sweep (`TinyLFU`).
5. Hand responses back to originating Netty EventLoops via their MPSC Response Queues for batch flushing.

---

## 🌐 Integration Context: Role of PoC-04 in Shiden

```text
       Netty Event Loops (I/O Layer - RFC-003)
                    │  │  │
    (xxHash3 Routing / Async MPSC Mailbox Enqueue)
                    ▼  ▼  ▼
   [ MPSC Worker Mailbox (JCTools) ] (1 Per Core - PoC-04)
                    │
   (Single-Threaded Drain Loop - RFC-004 Execution Model)
                    ▼
     Partition Worker Thread (Pinned CPU Core)
        ├──► Partition 0..63 Execution Routing
        ├──► Off-Heap Hash Index (PoC-03 / RFC-002)
        ├──► Slotted Storage Pages (PoC-02 / RFC-001)
        └──► Cooperative Time-Sliced Maintenance (50µs stepRehash)
```

1. **RFC-003 (Networking & Dispatching)**: Netty I/O threads parse versioned binary protocol frames, retain zero-copy `ByteBuf` handles, and push `CommandContext` structures directly into the target Worker Thread's MPSC Mailbox.
2. **RFC-004 (Partition Execution Kernel)**: Pinned worker threads drain their assigned Mailbox, route commands to local thread-confined partitions, execute storage mutations without locks, and release network buffers.

---

## 🎯 Scope & Verification Goals

### In-Scope:
1. Benchmark lock-free MPSC RingBuffer enqueue latency under 1, 4, 8, 16, and 32 concurrent producer threads.
2. Benchmark single-threaded consumer drain throughput across worker thread mailboxes and measure batch-drain efficiency.
3. Test CPU core pinning (`sched_setaffinity`) and quantify NUMA cross-socket interconnect memory penalties.
4. Implement 50µs time-sliced cooperative multitasking between command execution and background maintenance cycles (`stepRehash`).

### Out-of-Scope (Deferred):
* Distributed network transport (Netty TCP server integration is tested in RFC-003).
* Persistence WAL flushes to NVMe disk (deferred to PoC-06 / RFC-007).

---

## 🎓 Graduation Criterion

To graduate from PoC-04, we must prove:
> **Can an MPSC lock-free mailbox dispatcher sustain sub-100ns enqueue latency under 16 concurrent producer threads while driving single-threaded consumer execution above 50,000,000 commands/sec with zero JVM heap allocations?**
