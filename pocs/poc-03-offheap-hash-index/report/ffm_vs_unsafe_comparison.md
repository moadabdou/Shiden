# ⚡ Code Comparison: Safe FFM vs. Ultra-Fast Unsafe Path

This document provides a side-by-side technical comparison between [`OffHeapRobinHoodHashIndex.java`](file:///home/moadabdou/coding/serious_projects/Shiden/pocs/poc-03-offheap-hash-index/src/main/java/shiden/poc/hashindex/OffHeapRobinHoodHashIndex.java) (Java 21 Foreign Function & Memory API) and [`UnsafeFastPathRobinHoodHashIndex.java`](file:///home/moadabdou/coding/serious_projects/Shiden/pocs/poc-03-offheap-hash-index/src/main/java/shiden/poc/hashindex/UnsafeFastPathRobinHoodHashIndex.java) (`sun.misc.Unsafe` Production Hot Path).

Both classes implement the exact same **16-byte zero-GC Robin Hood Hash Index**, but they diverge fundamentally in **how memory is dereferenced**, **how CPU instructions are generated**, and **how hot paths are optimized**.

---

## 📊 Summary Comparison Matrix

| Feature / Aspect | Safe FFM Path (`OffHeapRobinHoodHashIndex`) | Unsafe Fast Path (`UnsafeFastPathRobinHoodHashIndex`) | Technical Rationale & Impact |
| :--- | :--- | :--- | :--- |
| **Primary Memory API** | `java.lang.foreign.MemorySegment` | `sun.misc.Unsafe` | FFM guarantees memory safety; `Unsafe` bypasses JVM wrappers for raw speed. |
| **Single Hot Lookup Latency** | ~11.79 ns / op | **3.21 ns / op** | **3.67x Faster**: `Unsafe` compiles to 1 native x86 `MOVQ` instruction. |
| **Address Calculation** | Relative Segment Offset (`bOffset`) | Raw Native Base Address + Offset (`bAddress`) | Avoids multiplying base segment pointer on every probe step. |
| **Bounds & Scope Checks** | Enforced per access (JVM VarHandle) | **Bypassed** (Direct pointer dereference) | Eliminates JVM method frame and bounds assertion overhead. |
| **Batch Prefetching** | Not Implemented | Implemented via `getBatch()` + `loadFence()` | Prefetches memory 4 requests ahead, dropping batch latency to **9.95 ns**. |
| **Eviction Support** | Reserved Layout | Implemented (`getFrequency`) | Extracts 16-bit offset 10 padding for W-TinyLFU eviction (RFC-008). |
| **Telemetry & Metrics** | Full Metrics (Probes, Rejections) | Minimalist (Production Lean) | Removes counter increments to keep CPU registers free for lookups. |

---

## 🔍 Detailed Code Comparison & Rationale

### 1. Memory Access & Dereferencing

#### **Safe FFM Path (`OffHeapRobinHoodHashIndex.java`)**
```java
// FFM API: Accesses off-heap memory via MemorySegment
long bOffset = (long) currIndex << BUCKET_SHIFT;
long word1 = segment.get(ValueLayout.JAVA_LONG, bOffset + WORD1_OFFSET);
long word0 = segment.get(ValueLayout.JAVA_LONG, bOffset + WORD0_OFFSET);
```

#### **Unsafe Fast Path (`UnsafeFastPathRobinHoodHashIndex.java`)**
```java
// Unsafe API: Accesses raw native address directly
long bOffset = (long) currIndex << BUCKET_SHIFT;
long bAddress = rawAddress + bOffset;
long word1 = UNSAFE.getLong(bAddress + WORD1_OFFSET);
long word0 = UNSAFE.getLong(bAddress + WORD0_OFFSET);
```

* **Why the difference?**
  * **FFM `MemorySegment.get()`**: Under the hood, Java 21's FFM API performs safety checks on every single access:
    1. Checks if the `Arena` scope is still alive (`arena.scope().isAlive()`).
    2. Checks if `bOffset + 8` is within the allocated segment boundaries.
    3. Invokes a `VarHandle` handle method.
  * **`Unsafe.getLong(bAddress)`**: `Unsafe` takes a raw 64-bit native memory address (`long rawAddress`). The C2 JIT compiler recognizes this intrinsic and compiles it down to a **single x86 `MOVQ` instruction** with **zero bounds checking**, **zero thread-scope checks**, and **zero stack frame allocation**.

---

### 2. Table Clearing & Initialization

#### **Safe FFM Path (`OffHeapRobinHoodHashIndex.java`)**
```java
private void clearTable() {
    for (int i = 0; i < capacity; i++) {
        long offset = (long) i << BUCKET_SHIFT;
        segment.set(ValueLayout.JAVA_SHORT, offset + DISTANCE_OFFSET, EMPTY_DISTANCE);
    }
}
```

#### **Unsafe Fast Path (`UnsafeFastPathRobinHoodHashIndex.java`)**
```java
private void clearTable() {
    for (int i = 0; i < capacity; i++) {
        long offset = (long) i << BUCKET_SHIFT;
        UNSAFE.putShort(rawAddress + offset + 8, EMPTY_DISTANCE);
    }
}
```

* **Why the difference?**
  * Direct byte writing via `UNSAFE.putShort` eliminates FFM `ValueLayout` abstraction during table allocation and resets.

---

### 3. Software Batch Prefetching (`getBatch`)

#### **Safe FFM Path (`OffHeapRobinHoodHashIndex.java`)**
* *Not present.* Performs single-key lookups synchronously.

#### **Unsafe Fast Path (`UnsafeFastPathRobinHoodHashIndex.java`)**
```java
public void getBatch(long[] keys, long[] resultsOut, int count) {
    int prefetchLookahead = 4;
    for (int i = 0; i < count; i++) {
        int prefetchIdx = i + prefetchLookahead;
        if (prefetchIdx < count) {
            long pHash = XxHash3.hash64(keys[prefetchIdx]);
            int pBucket = (int) (pHash & mask);
            long pAddress = rawAddress + ((long) pBucket << BUCKET_SHIFT);
            // Speculative load: reads first 2 bytes of the target 64-byte bucket cache line
            UNSAFE.getShort(pAddress + WORD1_OFFSET);
        }
        resultsOut[i] = get(keys[i]);
    }
}
```

* **Why the difference?**
  * In Shiden's **Partition Mailbox Engine (RFC-003)**, requests arrive in pipeline batches of 32–64 commands.
  * By computing the hash of item `i + 4` and speculatively reading `pAddress` 4 steps early, `getBatch` forces the CPU memory subsystem to issue a bus request to fetch the 64-byte bucket cache line into L1 cache ahead of time, breaking the sub-10ns barrier (**9.95 ns/key**).

---

### 4. Eviction Counter Access (`getFrequency`)

#### **Safe FFM Path (`OffHeapRobinHoodHashIndex.java`)**
* *Not present.* Focuses purely on basic index operations.

#### **Unsafe Fast Path (`UnsafeFastPathRobinHoodHashIndex.java`)**
```java
public short getFrequency(long key) {
    // ... probes bucket ...
    if (currFingerprint == fingerprint && currHashUpper == hashUpper) {
        return (short) ((word1 >>> 16) & 0xFFFF); // Extract 16-bit frequency from offset 10
    }
    return 0;
}
```

* **Why the difference?**
  * `UnsafeFastPathRobinHoodHashIndex` is designed for production integration with **W-TinyLFU off-heap eviction (RFC-008)**. It exposes methods to read the 16-bit access counter stored at offset 10 in the `word1` long.

---

### 5. Telemetry vs. Production Lean Design

#### **Safe FFM Path (`OffHeapRobinHoodHashIndex.java`)**
```java
// Maintains extensive metrics tracking
private long totalLookups = 0;
private long totalProbes = 0;
private long fingerprintEvaluations = 0;
private long fingerprintRejections = 0;
```

#### **Unsafe Fast Path (`UnsafeFastPathRobinHoodHashIndex.java`)**
* *No metrics counters.* Stripped of all extra fields and branching logic.

* **Why the difference?**
  * Incremental counter updates (`totalProbes++`, `fingerprintEvaluations++`) pollute CPU registers and force cache line writes. `OffHeapRobinHoodHashIndex` is used during testing and verification to analyze Robin Hood PSL statistics, while `UnsafeFastPathRobinHoodHashIndex` is kept completely lean for maximum throughput.

---

## 🎯 When to Use Which?

1. **Use [`OffHeapRobinHoodHashIndex.java`](file:///home/moadabdou/coding/serious_projects/Shiden/pocs/poc-03-offheap-hash-index/src/main/java/shiden/poc/hashindex/OffHeapRobinHoodHashIndex.java)**:
   * During **development, testing, and debugging**.
   * When you need safety assertions, probe sequence metrics (`maxProbeLength`, `fingerprintRejectionRate`), or strict memory lifetime management.

2. **Use [`UnsafeFastPathRobinHoodHashIndex.java`](file:///home/moadabdou/coding/serious_projects/Shiden/pocs/poc-03-offheap-hash-index/src/main/java/shiden/poc/hashindex/UnsafeFastPathRobinHoodHashIndex.java)**:
   * On **production hot paths**.
   * When executing high-throughput batch operations via Partition Mailboxes.
   * When every nanosecond matters (achieving **3.21 ns** single-key and **9.95 ns** batch latency).
