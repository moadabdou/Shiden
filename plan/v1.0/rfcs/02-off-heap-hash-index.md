# RFC-002: Off-Heap Hash Index

## 1. Abstract
This RFC outlines the design of the Off-Heap Hash Index for Shiden. Acting as the primary resolution mechanism mapping logical keys to physical `(Page ID, Slot ID)` storage pointers, this index must be incredibly fast, cache-friendly, and capable of holding millions of entries entirely off-heap. To guarantee lock-free operations, the index architecture strictly adheres to the Partition Ownership Model established in RFC-001.

## 2. Background & Goals
Standard Java `HashMap` incurs massive object overhead (`Node` or `Entry` objects) and pointer-chasing, leading to severe GC pauses and CPU cache misses at scale. To support Shiden's predictable sub-millisecond latencies, the Hash Index must be built using compact byte structures via the FFM API.

* **In-Scope Goals:**
  * Define an off-heap hash bucket layout that maximizes CPU cache-line utilization.
  * Determine the optimal open-addressing collision resolution strategy.
  * Design a non-blocking, incremental rehashing algorithm to prevent latency spikes during map resizes.
* **Out-of-Scope (Non-Goals):**
  * Persistent storage of the index (the index is entirely ephemeral and rebuilt from data pages/AOF upon crash recovery).
  * Key eviction logic (handled by the dedicated Eviction Index layer).

## 3. Proposed Architecture

### 3.1. The Partition Ownership Hierarchy
Following the Partition Ownership Model (RFC-001), there is **no global Hash Map**. The architecture enforces a strict separation of responsibilities:

```text
                Client
                   │
                   ▼
          Partition Router
                   │
                   ▼
         Owner Thread (Zero Locks)
                   │
     ┌─────────────┴─────────────┐
     │                           │
     ▼                           ▼
 Hash Index                 Eviction Index
     │                           │
     └─────────────┬─────────────┘
                   ▼
            Record Manager
                   ▼
             Slot Directory
                   ▼
             Page Manager
                   ▼
             Arena / FFM API
```
* Each partition has its own isolated, single-threaded Hash Index.
* **Zero Synchronization:** The Owner Thread is the only thread that reads, writes, or resizes this partition's index. There are absolutely no locks, no `CAS` operations, and no memory barriers required.

### 3.2. Probing Strategy: Robin Hood Hashing
To completely eliminate pointer-chasing and linked-list overhead (Separate Chaining), the index will use **Open Addressing**. Specifically, we will implement **Robin Hood Hashing**.

* **The Variance Problem:** Standard Linear Probing suffers from clustering. If an element collides, it gets pushed further down, creating chains of clustered data that ruin lookup times.
* **The Robin Hood Solution:** Robin Hood minimizes the variance in probe lengths. When a collision occurs during an insert, the algorithm compares the "probe distance" of the incoming entry versus the existing entry. If the incoming entry is further from its ideal bucket than the existing one, it "steals" the bucket, and the existing entry is bumped down the line (taking from the rich, giving to the poor).
* **Backward-Shift Deletion:** Because the partition is single-threaded, we do not need to use Tombstones (which slowly poison the table and increase probe lengths). Instead, upon deletion, the algorithm will use backward-shift deletion to slide the remaining cluster backwards, keeping the hash table perfectly compact.
* **Cache Locality:** By packing buckets sequentially in a contiguous memory segment, the CPU prefetcher will automatically load the next several candidate buckets into the L1 cache during a probe sequence, guaranteeing extreme read throughput.

### 3.3. Compact Bucket Layout
To ensure optimal memory alignment and maximize the number of buckets that fit into a 64-byte L1 cache line, the bucket layout must be tiny.

Storing variable-length strings directly in the Hash Index would ruin bucket uniformity. Instead, the index stores a **16-bit Key Fingerprint** to quickly reject 99% of collisions without jumping to the actual Data Page to perform a string comparison.

* **Bucket Structure (16 Bytes):**
  * `Probe Distance / State` (16-bit): Tracks the Robin Hood probe distance, or special state (`0xFFFF` = EMPTY).
  * `Fingerprint` (16-bit): A secondary hash fingerprint of the key. Used to instantly skip collisions.
  * `Slot ID` (16-bit): Offset index in the target Page's Slot Directory.
  * `Page ID` (32-bit): Pointer to the physical data page. (A 32-bit ID supports up to 16TB of addressable memory per partition when using 4KB pages, making 64-bit IDs unnecessary overhead).
  * `Reserved / Padding` (48-bit / 6 Bytes): Padding to ensure the bucket is exactly 16 bytes.

*Note: The fields are specifically ordered so that the hottest fields (`Distance` and `Fingerprint`) are evaluated first by the CPU.*

**Cache Line Math:** With 16-byte buckets, exactly **4 consecutive buckets fit perfectly into a single 64-byte CPU cache line**. A single memory read pulls 4 evaluation candidates simultaneously from RAM.

### 3.4. Incremental Rehashing (The Redis Pattern)
When a hash table exceeds optimal capacity, Robin Hood probe distances increase rapidly. Therefore, we cap the maximum load factor at **85%**.

When this threshold is reached, a traditional hash map allocates a new array 2x the size and pauses the world to copy all entries over, causing a massive tail latency spike (potentially hundreds of milliseconds). To maintain the $< 1\text{ ms}$ SLO, Shiden will implement **Incremental Rehashing**.

1. **Two Tables:** The Hash Index maintains two internal tables: `T0` (Current) and `T1` (Resizing).
2. **Trigger:** When `T0` hits 85% capacity, `T1` is allocated at double the size.
3. **Migration Unit:** Every subsequent `GET`, `PUT`, or `DEL` request pays a tiny "rehashing tax" by migrating a single **contiguous Robin Hood cluster** from `T0` to `T1`. Migrating whole clusters ensures that probe distances are not torn or corrupted across tables.
4. **Resolution:** During rehash, **all new `PUT` operations go directly to `T1`**. Lookups (`GET`) will check `T1` first, and if not found, fallback to `T0`. Over time, `T1` becomes the hotter table.
5. **Completion:** Once `T0` is entirely empty, its memory segment is freed back to the OS, and `T1` becomes the new `T0`.

### 3.5. Hash Index Operations (The Resolution Path)

The resolution pipeline bridges RFC-002 and RFC-001 together:

```text
Hash(Key) using xxHash3
   │
   ▼
Hash Index Bucket [ Distance | Fingerprint | Slot ID | Page ID ]
   │
   └─ Does Fingerprint match? ──▶ (Page ID, Slot ID)
                                         │
                                         ▼
                                   Slot Directory
                                         │
                                         ▼
                                   Record (Offset)
```

**Lookup (`GET`):**
1. Compute primary index via `xxHash3(Key) & (Capacity - 1)`. Compute the 16-bit `Fingerprint(Key)`.
2. Probe bucket. If `Bucket.Fingerprint != Fingerprint(Key)`, skip immediately (avoiding a costly cache miss to the Data Page).
3. If signatures match, follow the `(Page ID, Slot ID)` tuple.
4. Read the Record from the Data Page (RFC-001 Record Format). *Note: The Record format will embed the full 64-bit Hash of the key to skip recomputing it during collision verification and replication.*
5. If the strings match perfectly, return the Record. If it's a hash collision, resume probing.

## 4. Alternatives Considered

* **Alternative A: SwissTable / F14 (Google / Meta)**
  * *Why it was rejected (for now):* SwissTable is phenomenal. It uses a separate metadata array of 1-byte signatures and uses SIMD instructions (`_mm_cmpeq_epi8`) to scan 16 buckets in a single clock cycle. Future work may introduce SIMD-accelerated bucket scanning using the Java Vector API while preserving the Robin Hood semantics. For v1.0, Robin Hood provides deterministic, low-variance latency using standard memory accesses.
* **Alternative B: Separate Chaining (Java `HashMap` style)**
  * *Why it was rejected:* Requires allocating tiny linked-list `Node` objects for every collision. This results in pointer chasing, destroying CPU cache locality and adding significant memory overhead.
