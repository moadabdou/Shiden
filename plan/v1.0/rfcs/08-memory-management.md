# RFC-008: Memory Management

**Depends on:** RFC-001 (Storage Engine), RFC-002 (Hash Index), RFC-004 (Execution Model)

## 1. Abstract
While RFC-001 defined the logical layout of the storage engine (Records, Slots, Pages), this RFC defines how Shiden interacts with the Operating System to manage physical RAM. Because Shiden entirely bypasses the JVM Garbage Collector, it must implement its own hierarchical allocators, automated defragmentation routines, and NUMA-aware memory mapping to saturate high-end hardware.

## 2. Background & Goals
Managing hundreds of gigabytes of off-heap memory requires careful coordination with the OS Kernel to avoid page faults, TLB thrashing, and physical fragmentation.
* **In-Scope Goals:**
  * Define OS-level memory mapping (Huge Pages & NUMA pinning).
  * Design the strict Allocator Hierarchy (Arena $\rightarrow$ Allocator Page $\rightarrow$ Storage Page).
  * Detail the heuristic-driven Page Defragmentation algorithm.
  * Establish allocator observability and memory pressure mechanisms.
* **Out-of-Scope:**
  * Java Garbage Collection tuning (Shiden's data path produces zero GC garbage).

## 3. OS-Level Memory Interactions

### 3.1. Huge Pages
Standard Linux memory pages are `4KB`. If a Shiden node manages `512GB` of RAM, the CPU must maintain over `134 million` page table entries. 
* **The Solution:** Shiden utilizes **Huge Pages** (e.g., 2MB or 1GB sizes). Depending on the environment configuration, this may use explicit huge pages (`MAP_HUGETLB`) or platform-specific mechanisms like `madvise(MADV_HUGEPAGE)` for Transparent Huge Pages (THP).
* **Impact:** This dramatically reduces TLB misses and page-table walk overhead, allowing the CPU to spend its cycles executing database logic rather than resolving memory addresses.

### 3.2. Strict NUMA Pinning & First-Touch Allocation
As defined in RFC-004, Worker Threads are pinned to physical CPU cores. However, the memory they allocate must also reside on the same physical CPU socket (NUMA node).
* Because Linux enforces a "First-Touch" allocation policy, the physical RAM backing a virtual memory mapping is not assigned until the memory is written to.
* Therefore, the **owning Worker Thread** must be the thread that performs the initial FFM `Arena` memory mapping and writes the initial zeros. This guarantees the OS will satisfy the allocation using RAM from the local CPU socket, permanently avoiding Infinity Fabric / QPI interconnect latency.

## 4. The Allocator Hierarchy

To avoid ambiguity, Shiden strictly separates OS memory from Allocator memory and Storage memory.

```text
OS Memory (Huge Page Region - 2MB/1GB)
       │
       ▼
Arena Region (1GB block, expands by mapping additional Huge Pages)
       │
       ▼
Allocator Pages (e.g., 16KB blocks managed by Extent-Based Free Lists)
       │
       ▼
Storage Pages (The logical Record/Slot structures defined in RFC-001)
       │
       ▼
Records (Client Data)
```

### 4.1. The Page Allocator (Extent-Based Free Lists)
When Storage Pages are created or destroyed, the intermediate Allocator Pages must be tracked.
* Shiden uses **Extent-Based Free Lists** to guarantee $O(1)$ allocation times. Free Allocator Pages are tracked in arrays categorized by contiguous extents (e.g., a list for 1 free page, a list for 2 contiguous free pages, a list for 4, etc.).
* When space is required, the allocator pops an extent from the exact-size free list.

### 4.2. The Slab Allocator (Fixed-Size Metadata)
Dynamic allocation leads to external fragmentation. However, Shiden has several structures that are strictly fixed in size (e.g., the 16-Byte Hash Index Buckets from RFC-002, or networking Mailbox nodes).
* For these structures, Shiden uses a **Slab Allocator**.
* A large chunk of memory (a Slab) is pre-divided into perfectly uniform blocks.
* The blocks are strictly **64-byte cache-line aligned** to prevent false sharing and optimize CPU prefetching.

## 5. Defragmentation & Memory Reclamation

As clients constantly `PUT`, `UPDATE`, and `DEL` keys, Storage Pages accumulate "holes" (internal fragmentation).

### 5.1. Utilization Tracking
The Slot Directory at the top of every Storage Page (RFC-001) tracks the exact number of bytes actively occupied by live records versus the total capacity of the page.

### 5.2. Page Evacuation (Compaction)
If a page drops below a configurable threshold (e.g., 30% utilization), keeping it in RAM is a waste of expensive hardware.
1. **Allocate:** A brand new, tightly packed destination Storage Page is allocated.
2. **Copy:** The Partition Thread iterates the old page's Slot Directory and copies only the *live* records into the new page.
3. **Relink:** The Hash Index (RFC-002) is updated by modifying the bucket pointer **atomically** to point to the new `(PageID, SlotID)`.
4. **Reclaim:** The old, heavily fragmented Allocator Pages are wiped and added back to the Extent-Based Free List.

### 5.3. Bounded Maintenance Budget
To maintain sub-millisecond network latencies, the Partition Thread cannot evacuate thousands of pages at once. 
* Evacuation is cooperative. During its maintenance phase, the Partition Thread is given a **bounded maintenance budget** (e.g., maximum 50µs execution time, or a maximum of 10 pages evacuated) before it must yield back to the Netty network queues.

### 5.4. Safe Reclamation
Within a partition's owned memory, Shiden does not require hazard pointers or epoch-based reclamation (EBR) because no other execution context may concurrently dereference those pages. Once the Partition Thread removes a page from active circulation, the memory can be reused instantly.

### 5.5. Memory Pressure
If the Arena Region cannot expand (i.e., physical RAM is entirely exhausted), the allocator signals Memory Pressure.
* Shiden immediately begins failing incoming `PUT` requests with `OOM_ERROR`.
* Concurrently, the Eviction Index (detailed in a future RFC) is triggered to violently purge keys based on TTL or LRU policies until utilization drops below the high-water mark.

## 6. Allocator Observability
To ensure operators can debug memory leaks and fragmentation, the allocator tracks and exposes the following hardware metrics (RFC-010):
* `bytes_allocated`
* `bytes_free`
* `fragmentation_ratio`
* `huge_pages_active`
* `numa_locality_hits` (Ensuring the First-Touch policy is succeeding).

## 7. Guarantees
This RFC establishes the following architectural guarantees:
* **Zero GC Pauses:** The data path bypasses the JVM heap entirely.
* **TLB Optimization:** Off-heap memory utilizes OS Huge Pages.
* **$O(1)$ Allocation:** Extent-Based Free Lists and Slab Allocators guarantee instant memory reservations.
* **Constant Fragmentation:** The heuristic-driven evacuator guarantees physical memory fragmentation never degrades the system over time.
* **Lock-Free Reclamation:** The Shared-Nothing architecture allows instant memory reuse without the overhead of Hazard Pointers.
