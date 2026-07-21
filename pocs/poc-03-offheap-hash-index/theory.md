# Theory Checkpoint: Off-Heap Hash Index (Robin Hood & Incremental Rehashing)

To support Shiden's predictable sub-millisecond data grid latencies, key-to-record resolution must execute in single-digit nanoseconds without incurring JVM heap allocations, pointer-chasing, or Stop-The-World (STW) hash table resize pauses. 

PoC-03 validates the design of Shiden's **Off-Heap Hash Index (RFC-002)**, built directly on top of the thread-confined **`SlabAllocator` (PoC-02)**.

---

## 🧠 Core Concepts

### 1. The Pointer-Chasing Problem (Java `HashMap` Defect)
Standard Java `java.util.HashMap` stores entries in linked-list / red-black tree nodes (`Node<K,V>`). 
* **Pointer Chasing**: Each lookup follows object references (`node.next`), causing L1/L2 CPU cache misses.
* **Heap Garbage**: Millions of `Node` objects trigger heavy JVM GC tracing and Stop-The-World pauses.
* **Memory Bloat**: Each node carries 16-byte object headers + 8-byte reference pointers.

### 2. Robin Hood Open Addressing
To eliminate pointers and guarantee cache locality, the Hash Index stores entries sequentially in a contiguous off-heap memory array (Open Addressing).
* **Linear Probing Defect**: Standard linear probing forms large contiguous "clusters" of occupied buckets, causing probe lengths to explode under high load factor.
* **The Robin Hood Invariant**: Robin Hood minimizes probe length variance. During insertion, if an incoming key has a longer **Probe Sequence Length (PSL)** than the entry currently occupying a bucket, the incoming key "steals" the bucket, and the existing entry is pushed down ("take from the rich, give to the poor").
* **Cache Locality**: 4 consecutive 16-byte buckets fit perfectly into a single **64-byte CPU cache line**. A single RAM read pulls 4 evaluation candidates simultaneously into L1 cache.

### 3. 16-Byte Compact Bucket Layout
To maximize cache density, bucket structs are kept tiny (16 bytes):

```text
┌──────────────────┬──────────────────┬──────────────────┬──────────────────┐
│  Distance (16b)  │ Fingerprint(16b) │   Slot ID (16b)  │   Page ID (32b)  │
└──────────────────┴──────────────────┴──────────────────┴──────────────────┘
```

* **Probe Distance / State (16-bit)**: Distance from ideal bucket (`0xFFFF` = EMPTY).
* **Key Fingerprint (16-bit)**: Secondary hash fingerprint (`xxHash3`). Rejects 99.996% of key collisions in L1 cache without fetching the actual payload page from off-heap memory.
* **Slot ID (16-bit)**: Index inside target Storage Page's Slot Directory.
* **Page ID (32-bit)**: Physical off-heap Storage Page ID (supports 16TB addressable storage per partition).
* **Reserved / Padding (48-bit)**: Alignment padding to guarantee exactly 16-byte bucket boundaries.

### 4. Incremental Rehashing (Eliminating Resize STW Spikes)
Traditional hash tables pause all operations to allocate a 2x array and re-hash millions of keys at once, causing 100ms+ latency spikes. 

Shiden implements **Incremental Rehashing**:
1. **Dual Tables**: Maintains two internal tables: `T0` (Current) and `T1` (Resizing).
2. **Trigger**: When `T0` hits 85% load factor, `T1` is allocated at double capacity using `SlabAllocator`.
3. **Migration Tax**: Every subsequent `PUT`, `GET`, or `DEL` operation migrates one contiguous Robin Hood cluster from `T0` to `T1`.
4. **Operation Routing**: All new `PUT`s write directly to `T1`. `GET` lookups check `T1` first, falling back to `T0`.
5. **Completion**: Once `T0` is empty, its off-heap memory is freed, and `T1` becomes the new `T0`.

### 5. Backward-Shift Deletion (No Tombstones)
In multi-threaded hash tables, deletions leave "Tombstones" that poison the table and increase probe lengths over time. 
Because Shiden operates under a single-threaded **Partition Ownership Model**, deletions use **Backward-Shift Deletion**:
* When a slot is freed, adjacent elements in the probe cluster are shifted backwards by 1 slot until an element with `PSL == 0` or an empty slot is reached.
* Keeps the hash table perfectly compact with zero tombstone degradation.

---

## 🎯 Scope & Verification Goals

### In-Scope:
1. Implement single-threaded off-heap Robin Hood Hash Index using FFM `MemorySegment` and `SlabAllocator`.
2. Implement 16-bit key fingerprinting and benchmark false-positive rejection rates.
3. Implement Incremental Rehashing (`T0` $\rightarrow$ `T1` cluster tax) and measure p99 tail latency during 2x map expansion.
4. Compare Robin Hood lookup latencies at 50%, 70%, 85%, and 95% load factors.

### Out-of-Scope (Deferred):
* Disk persistence of index (ephemeral index, rebuilt from WAL/Storage Pages on restart).
* Eviction policy logic (LRU/LFU handled by Eviction Index layer).

---

## 🎓 Graduation Criterion

To graduate from PoC-03, we must prove:
> **Can an off-heap Robin Hood Hash Index deliver sub-15ns lookups at 85% load factor while maintaining zero STW latency spikes during 2x incremental map rehashing?**
