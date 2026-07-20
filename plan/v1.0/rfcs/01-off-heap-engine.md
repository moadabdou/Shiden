# RFC-001: Off-Heap Memory Manager

## 1. Abstract
This RFC outlines the design of Shiden's Off-Heap Memory Manager, the core storage engine of the grid. By utilizing Java 21's Foreign Function & Memory (FFM) API, the engine bypasses the JVM Garbage Collector (GC) entirely. Taking architectural inspiration from Hazelcast's High-Density Memory Store (HDMS) and Apache Ignite's Page Memory architecture, this component manages raw OS memory using fixed-size blocks to prevent fragmentation, enabling Shiden to hold massive datasets with predictable sub-millisecond latency.

## 2. Background & Goals
In standard Java applications, storing millions of objects on the heap leads to catastrophic "Stop-The-World" (STW) GC pauses as the heap grows (e.g., beyond 32GB). High-performance IMDGs must maintain predictable tail latencies regardless of data volume.

* **In-Scope Goals:**
  * Eliminate GC pauses by allocating data structures entirely in native memory.
  * Prevent memory fragmentation over time by utilizing a Page-based allocation strategy.
  * Implement near-constant time Least Recently Used (LRU) eviction.
* **Out-of-Scope (Non-Goals):**
  * Persistent disk storage (to be handled by a separate AOF/Snapshotting RFC).
  * Network serialization (handled by the RESP/SBE networking layer).

## 3. Proposed Architecture

### 3.1. The Four-Layer Storage Stack
To ensure the off-heap engine is independently testable, modular, and easy to evolve, it is organized into four explicit conceptual layers:

```text
Storage Engine
 │
 ├── Allocation Layer
 │      Arena
 │      Memory Segments
 │      Extents
 │
 ├── Page Layer
 │      Headers
 │      Slot Directory
 │      Compaction
 │
 ├── Record Layer
 │      Record Format
 │      Expiration
 │      Versioning
 │
 └── Index Layer
        Hash Index
        Eviction Index
```

1. **Allocation Layer:** Owns the raw native memory chunks mapped directly from the OS.
2. **Page Layer:** Handles the allocation, freeing, and metadata tracking of fixed-size pages.
3. **Record Layer:** Packs and unpacks individual records, manages the Slot Directory, and executes relocations.
4. **Index Layer:** Maps logical keys to physical `(Page ID, Slot ID)` pointers, and plugs into eviction policies.

### 3.2. Page Memory Architecture (Inspired by Apache Ignite)
To avoid the external fragmentation inherent in dynamic allocations, physical native memory is managed through a strict Allocator Hierarchy (RFC-008): 1GB Arena Regions are mapped from the OS in 16KB Allocator Page Extents, which are logically carved into **fixed-size 4KB Storage Pages**. 

* **External Fragmentation Solution:** When an entire page is evicted or freed, it creates a perfectly sized hole for exactly one new page, eliminating external memory fragmentation.
* **Internal Fragmentation Solution (Data Packing):** A single 4KB page will not just store one small 50-byte key-value pair. Following Apache Ignite's model, we will pack multiple key-value entries into a single page sequentially. 
* **Large Values & Cache Locality:** If a value exceeds the page size, Shiden uses **Contiguous Extent Allocation**. If a value requires 3 pages, the engine allocates a 3-page contiguous extent whenever possible, guaranteeing sequential memory access.
* **Extent Allocation Fallback:** If no contiguous extent of sufficient size exists due to memory fragmentation, the allocator falls back to allocating multiple smaller extents referenced through a compact extent descriptor.
* **Extent Table:** To achieve near-constant time allocation speeds, the system maintains segregated free extent lists based on size classes:
  ```text
  Extent Size (1 page)  -> Free List [Page 10, Page 92]
  Extent Size (2 pages) -> Free List [Page 44-45]
  Extent Size (4 pages) -> Free List [Page 100-103]
  ```
  When allocating a 4-page extent, the manager simply pops from `List[4]`. If empty, it splits a larger extent or merges smaller ones.

### 3.3. Architecture Diagrams

#### Memory Hierarchy
```text
Arena
 │
 ├─────────────── Memory Segment 0
 │     ├── Page 0
 │     ├── Page 1
 │     ├── Page 2
 │
 ├─────────────── Memory Segment 1
 │     ├── Page 500
 │     ├── Page 501
 │
 └─────────────── Memory Segment 2
```

#### Page Layout & Hash Index Resolution
```text
Hash Index
   │
   └─ "user:123" ──▶ (Page 0, Slot 3)
                            │
+---------------------------│--------------------------+
| Page 0 (e.g., 4096 Bytes) │                          |
| +-------------------------│------------------------+ |
| | 64-byte Header          │                        | |
| +-------------------------│------------------------+ |
| | Payload →               ▼                        | |
| | Record 1  [ Offset 980 ]◄──────────────────┐     | |
| | Record 2                                   │     | |
| | Record 3                                   │     | |
| |                                            │     | |
| |              Free Space                    │     | |
| |                                            │     | |
| | ← Slot Directory (2-byte offsets)          │     | |
| | Slot3 [ Offset 980 ] ──────────────────────┘     | |
| | Slot2                                            | |
| | Slot1                                            | |
| +--------------------------------------------------+ |
+------------------------------------------------------+
```

### 3.4. Page Layout & Headers
Every page will maintain a strict binary layout. The header is exactly **64 bytes** to align perfectly with a standard CPU cache line. Hot fields are grouped at the beginning to maximize L1 cache locality.

* **Header Structure (64 Bytes):** 
  * `Flags` (8-bit): Bitmask for various page states.
  * `Free Space Offset` (16-bit): Pointer to the byte offset where the next piece of data can be written.
  * `Slot Directory Offset` (16-bit): Pointer to the start of the backward-growing Slot Directory.
  * `Live Record Count` (16-bit): Number of active key-value entries.
  * `Tombstone Count` (16-bit): Number of deleted records, used to trigger compaction.
  * `Magic` (32-bit): A fixed identifier (e.g., `0x53484944` "SHID"). 
  * `Checksum` (32-bit): A CRC32 hash of the entire page content. Detects accidental corruption and validates page integrity.
  * `LSN (Log Sequence Number)` (64-bit): Monotonic page version. (Reserved for future persistence/recovery RFCs).
  * `Page ID` (64-bit): Unique identifier/offset for the page.
  * `Page Type` (8-bit): Distinguishes between Data Pages, Index Pages, and Extent Metadata Pages.
  * `Next Page Pointer` (64-bit): Used for large values spanning multiple contiguous pages.
  * `Padding` (22 bytes): Empty space to pad the header to exactly 64 bytes.

*(Note: `Free Bytes` is derived dynamically as `SlotDirectoryOffset - FreeSpaceOffset`)*.

* **Payload (Forward Growth):** The bytes following the header are dedicated to packed key-value entries. Grows *forward*.
* **Slot Directory (Backward Growth):** The very end of the page contains an array of 2-byte integers. Grows *backward*.

### 3.5. Record Format Layout
Inside the Payload area, individual records are packed using the following binary format:
* `Record Length` (32-bit): Total size of the entire record (includes metadata, key, value, and padding).
* `Version` (64-bit): Distinguished from Sequence Number. Used exclusively for optimistic locking (CAS).
* `Compaction Epoch` (32-bit): Tracks the compaction cycle. Incremented globally per-partition by the Owner Thread. Prevents ABA problems during snapshotting or replication.
* `State` (8-bit): Defines the exact lifecycle state (`ACTIVE`, `TOMBSTONE`, `EXPIRED`, `LOCKED`).
* `Expiration Timestamp` (64-bit): A Unix epoch timestamp. If `0`, it never expires.
* `Flags` (8-bit): Bitmask representing data properties (`is_compressed`).
* `Key Length` (16-bit): Allows keys up to 64KB.
* `Value Length` (32-bit): Allows values up to 4GB.
* *(Note: Record-level CRC32 is intentionally omitted to save CPU cycles. The Page Checksum provides sufficient corruption detection).*
* `Key Bytes` (Variable): Raw bytes.
* `Value Bytes` (Variable): Raw bytes.
* **Memory Alignment:** The total size of the record will be mathematically padded to a strict **16-byte boundary**. This guarantees that all 64-bit fields fall on aligned CPU boundaries, preventing hardware penalties and enabling modern 128/256-bit SIMD (AVX) instructions for vectorized scans.

### 3.6. FFM API Integration (Java 21)
* **Allocation:** Following the Partition Ownership Model (§3.8), each partition uses a thread-confined arena (`Arena.ofConfined()`). This eliminates JVM thread-synchronization checks inside FFM scope validations while guaranteeing zero lock contention. The abstraction isolates the allocation backend so alternative FFM strategies can be evaluated without changing higher layers.
* Internal data structures will use **64-bit integer byte offsets** (raw pointers).

### 3.7. Eviction Index Pluggability
To keep the architecture modular: `Page Manager` $\rightarrow$ `Record Manager` $\rightarrow$ `Hash Index` $\rightarrow$ `Eviction Index`.
* **The Eviction Index:** A dedicated off-heap structure (array-backed doubly linked list) tracks the `(Page ID, Slot ID)` of keys.
* **Pluggability:** Because the Eviction Index is decoupled from the physical Record layout, Shiden can swap LRU for LFU without altering the core memory engine.

### 3.8. Concurrency: The Partition Ownership Model
Instead of a shared-memory model relying on locks, Shiden utilizes a **Partition Ownership Model**.
* **Architecture:** The data grid is divided into fixed logical partitions (e.g., 64 partitions). Each partition is strictly "owned" by exactly one execution thread.
* **Zero Locks:** Concurrent modification is physically impossible. There are no locks or `CAS` operations in the memory engine.
* **Asynchronous Message Passing:** Owner Threads never directly access another partition's pages. Cross-partition operations occur exclusively through asynchronous message passing. Netty I/O threads enqueue requests to the Owner Thread's queue.

## 4. Core Operational Flows

### 4.1. Lifecycle States
**Page Lifecycle:**
1. **FREE:** Exists in the Extent Table. No active data.
2. **ALLOCATED:** Pulled from the Extent Table to serve a write.
3. **ACTIVE:** Actively holding records.
4. **EVICTING / EVACUATING:** Active records are being relocated to a new page.
5. **FREE:** Returned to the Extent Table.

**Record Lifecycle:**
`ACTIVE` $\rightarrow$ `TOMBSTONE` (upon deletion) $\rightarrow$ **Compacted Away** (physically removed during in-page compaction or page evacuation).

### 4.2. Record Allocation Algorithm (The Write Path)
1. **Find Page:** Check the active `Data Page` for this partition.
2. **Check Space:** Is `(SlotDirectoryOffset - FreeSpaceOffset) >= RecordLength + 2`?
3. **If Yes:** Write record at `FreeSpaceOffset`, prepend slot to Slot Directory, update Hash Index to `(Page ID, Slot ID)`.
4. **If No:** Query Extent Table for a new `FREE` page, make it active, and retry.

### 4.3. Compaction & Memory Reclamation
Compaction is incremental and split into two distinct mechanisms to avoid latency spikes:

1. **In-Page Compaction:**
   * **Trigger:** Too many `Tombstones` in a specific page.
   * **Action:** The Owner Thread slides active records forward to overwrite Tombstones within the same page.
   * **Benefit:** Local 2-byte slot offsets are updated. **The global Hash Index is completely untouched.**

2. **Page Evacuation:**
   * **Trigger:** Overall page utilization drops severely (e.g., `< 20%`), OR the compaction benefit exceeds a configured threshold (e.g., `30%`).
   * **Action:** The Owner Thread copies the remaining live records to a brand new page.
   * **Overhead:** The global Hash Index *must* be updated to point to the new `(Page ID, Slot ID)`. The old page is returned to the Extent Table.

## 5. Cross-Node & Networking Protocol
* **Zero-Serialization Storage:** The memory manager stores data exactly in the SBE byte format received from the network layer.

## 6. Alternatives Considered
* **Alternative A: Standard JVM Heap (`HashMap<String, byte[]>`)**
  * *Rejected:* STW GC pauses violate the $< 1\text{ ms}$ SLO.
* **Alternative B: Legacy `sun.misc.Unsafe` or Direct `ByteBuffer`**
  * *Rejected:* `ByteBuffer` is limited to 2GB. `Unsafe` is deprecated. Java 21's FFM API replaces both safely.

## 7. SRE & Observability Plan
* **Metrics:** 
  * `shiden.memory.allocated_bytes`: Total off-heap memory.
  * `shiden.memory.active_pages`: Number of pages holding live data.
  * `shiden.memory.evacuation_rate`: Number of Page Evacuations per second.
  * `shiden.memory.fragmentation_ratio`: Track wasted bytes.
* **Failure Modes:** 
  * **Native OOM:** If native memory allocation fails, trigger emergency aggressive eviction and return `503 Service Unavailable` for writes.

## 8. Appendix: Future Enhancements
The architecture intentionally leaves room for advanced storage optimizations:
* **NUMA-Local Arenas:** Pinning Owner Threads to specific NUMA nodes with node-local memory pools to avoid QPI/cross-socket latency.
* **Huge Pages (`MAP_HUGETLB`):** Utilizing 2MB or 1GB OS pages to reduce TLB misses.
* **Vectorized Scans:** Leveraging SIMD (AVX-512) via the Java Vector API for ultra-fast full-table parallel scanning.
* **Adaptive Page Sizes:** Dynamically sizing pages based on workload profiles.
* **Tiered Memory:** Transparently spilling colder extents to NVMe SSDs.
* **Off-Heap Hash Index Implementation:** A dedicated RFC to cover bucket layouts, probing strategies, and incremental rehashing.
