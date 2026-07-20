# Theory Checkpoint: Off-Heap Arena Allocator

High-performance storage engines cannot rely on the standard JVM garbage-collected heap. Doing so introduces non-deterministic Stop-The-World (STW) pauses that destroy tail latency (p99/p99.9). This PoC validates manual off-heap memory management using the modern Java Foreign Function & Memory (FFM) API.

---

## 🧠 Concepts Required

1. **Foreign Function & Memory (FFM) API (JEP 442/454)**:
   - **`Arena`**: Controls the lifecycle of native memory segments. Confined arenas (`ofConfined()`) are bound to a single thread; shared arenas (`ofShared()`) allow multi-threaded access. Because Shiden assigns each partition to a dedicated owner thread (Partition Ownership Model), PoC-02 strictly uses `Arena.ofConfined()`. This eliminates JVM thread-synchronization checks during pointer dereferencing while guaranteeing zero lock contention.
   - **`MemorySegment`**: Represents a contiguous region of memory (off-heap or heap).
   - **`MemoryLayout` / `VarHandle`**: Provides structured, type-safe access to memory offsets without manual byte-offset calculations, compiling down to raw pointer dereferencing.

2. **Garbage Collector (GC) Tracing Overhead**:
   - In standard Java, object references are tracked in a graph. During GC cycles, the JVM traverses this graph (tracing) to identify live objects. As the heap grows to contain millions of objects, GC tracing and bookkeeping costs increase, leading to latency spikes and longer tail latencies.
   - Off-heap memory is invisible to the GC's tracing engine. The JVM only tracks a tiny wrapper object on the heap, while the actual data lies in native RAM.

3. **Slab Allocation (Fixed-Size Allocator)**:
   - *Problem*: Dynamic off-heap allocation (like `malloc` or repeatedly allocating small `MemorySegment` instances) causes external memory fragmentation.
   - *Solution*: Pre-allocate a large chunk of memory (a Slab) and split it into equal, fixed-sized slots (e.g., 64 bytes, 128 bytes, 256 bytes). Allocation and deallocation are $O(1)$ operations, typically using a bitmap or a free-list stack.

4. **Arena/Bump Allocation (Linear Allocator)**:
   - Allocates memory sequentially by incrementing a cursor pointer (bump allocator).
   - Very fast ($O(1)$ allocation), but individual objects cannot be freed. The entire Arena must be reclaimed all at once, making it ideal for transaction contexts or batch processing.

5. **Memory Alignment & CPU Cache Lines**:
   - Modern CPUs load data from RAM in **64-byte cache lines**.
   - **Data Alignment**: 64-bit values (like `long` or pointers) should be aligned to 8-byte boundaries. Record boundaries should be aligned to 16-byte boundaries. If data spans across two cache lines (misalignment), the CPU must perform two memory cycles instead of one, cutting throughput in half.
   - **False Sharing**: Multiple threads modifying different variables on the same cache line cause the CPU to constantly invalidate cache lines across cores. Structs must be padded to prevent false sharing.

6. **Huge Pages (`MAP_HUGETLB`)**:
   - Standard OS pages are 4KB. Virtual-to-physical address translations are cached in the CPU's Translation Lookaside Buffer (TLB).
   - With huge datasets, 4KB pages exhaust the TLB, causing constant page table walks. Using 2MB or 1GB Huge Pages increases TLB coverage, reducing translation overhead.

---

## 🎯 Ownership Model

To ensure memory safety and prevent race conditions without locks, PoC 2 adheres to a strict memory ownership model:
* **One Arena per Partition Thread**: Each partition is assigned its own confined memory arena.
* **No Shared Ownership**: Arenas and their allocated segments never cross ownership boundaries during regular operations.
* **Immutable Snapshots**: Cross-thread transfers (e.g., for persistence or replication) happen strictly through immutable snapshots.
* **Detached Segment Hand-off**: The background persistence/IO thread receives detached segments, preventing concurrent access and protecting confined memory lifecycle rules.
* **Lifetime Alignment**: The lifetime of the Arena is strictly tied to the lifetime of its owner Partition.

---

## 🌐 Integration Context: Role of PoC-02 in Shiden

To understand why PoC-02 focuses on the Slab Allocator and FFM API, it is essential to see where this component lives inside Shiden's overall architecture:

### 1. What PoC-02 Directly Powers in Shiden (Fixed Metadata)
The **Slab Allocator** validated in PoC-02 is the low-level, sub-20ns allocator used for fixed-size internal engine structures. Each partition owner thread instantiates multiple specialized `SlabAllocator` instances tailored for specific subsystems:
* **RFC-002 (Off-Heap Hash Index)**: Configured with 16-byte slots (`slotSize=16, align=8`) to store index bucket entries mapping `Hash -> (PageID, SlotID)`.
* **RFC-003/004 (Execution & Messaging Layer)**: Configured with 64-byte slots (`slotSize=64, align=64`) for cache-line-aligned RingBuffer/Mailbox nodes for zero-copy message passing between event loops.
* **RFC-008 (Allocator Metadata)**: Configured with 32-byte slots (`slotSize=32, align=16`) for Extent Descriptors in the Page Allocator's free-list tables.

### 2. Why `Arena.ofConfined()` is Used (Zero-Lock Thread-Per-Partition)
Since each partition is strictly owned by a single execution thread, each partition thread owns its own `Arena.ofConfined()`. There is no need to test `Arena.ofShared()` for hot-path allocations because no two threads ever contend for the same partition slab.

### 3. How it Interacts with the Slotted-Page Engine (Client Data)
* **PoC-02 (Slab Allocator)**: Handles **internal metadata** (fixed size, $O(1)$ bitmap lookup, sub-20ns speed, zero internal fragmentation).
* **RFC-001 (Slotted Storage Engine)**: Handles **client payloads** (variable size, packed into 4KB pages with 2-byte slot directories, compacted incrementally).

Together, they form Shiden's complete GC-free off-heap memory architecture.

---

## 📚 Reference Material

* **Slab Allocator Foundation**: *The Slab Allocator: An Object-Caching Kernel Memory Allocator* (Jeff Bonwick, USENIX 1994). [PDF Link](https://www.usenix.org/legacy/publications/library/proceedings/bos94/pdfs/bonwick.pdf)
* **JDK FFM Guide**: *JEP 454: Foreign Function & Memory API* (JDK 22). [OpenJDK JEP Link](https://openjdk.org/jeps/454)
* **Database Off-Heap Memory Design**: *Ignite Page Memory Architecture* (Apache Ignite Wiki). [Link](https://cwiki.apache.org/confluence/display/IGNITE/Ignite+Durability+Architecture)
* **Tail Latency Mitigation**: *The Tail At Scale* (Jeffrey Dean and Luiz André Barroso, Communications of the ACM, 2013). [ACM Link](https://cacm.acm.org/magazines/2013/2/160173-the-tail-at-scale/fulltext)

---

## ❓ Core Questions

### 1. Why does this allocator model exist?
Traditional JVM allocation creates heap garbage. When storing millions of items, the GC overhead makes low-latency SLAs impossible. A manual off-heap allocator bypasses the JVM GC entirely, putting memory lifecycle management directly in the developer's control.

### 2. What problem does it solve?
It prevents GC-induced tail-latency spikes (p99/p99.9) while avoiding the external memory fragmentation associated with random malloc/free calls through structured Slab and Page allocation.

### 3. What assumptions does it make?
* It assumes the developer will explicitly and correctly manage object lifetimes (avoiding memory leaks and use-after-free bugs).
* It assumes the JVM JIT compiler can optimize FFM access to raw assembly instructions without added runtime bounds-checking overhead.
* It assumes target OS support for page pinning and numa configuration.

---

## 🛡️ Scope Boundaries & Deferrals

To avoid premature over-engineering during this validation phase, PoC 2 establishes strict boundaries:
* **In-Scope**: Proving Java FFM API performance, validating GC pause elimination, verifying memory alignment, and implementing a basic thread-local slab/arena allocator.
* **Out-of-Scope (Deferred to RFC-008 & Core Implementation)**: Native OS Huge Pages (`MAP_HUGETLB`), native NUMA node affinity binding/pinning, lock-free/concurrent slab allocators, and multi-threaded shared arenas.

---

## 🎓 Graduation Criterion

To graduate from this PoC, you must be able to answer this question without referring to notes:
> **How does the Panama FFM API differ from legacy `sun.misc.Unsafe` in terms of memory safety, and how do we design an off-heap slab allocator that avoids application-level heap allocations, minimizes FFM wrapper allocations, and achieves sub-20 ns allocations on uncontended hot paths?**
