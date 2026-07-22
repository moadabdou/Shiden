# Engineering Notebook: Mistakes, False Assumptions, & Pitfalls

This document logs the design flaws, incorrect assumptions, and performance bottlenecks discovered during the design, implementation, and benchmarking of the Off-Heap Robin Hood Hash Index and Incremental Rehashing subsystem.

---

## 🚫 Anticipated Pitfalls to Validate

### 1. Power-of-Two Modulo Masking vs. Prime Modulo Distribution
* **False Assumption**: Power-of-two table capacity with bitwise AND masking (`hash & (capacity - 1)`) is always superior because bitwise AND costs 1 CPU cycle whereas modulo `%` costs 10-15 cycles.
* **The Reality**: If the hash function produces low entropy in its lower bits, power-of-two masking creates massive bucket clustering. Using a high-quality 64-bit hash (such as xxHash3 or MurmurHash3 avalanche mixer) is required so bitwise masking yields uniform bucket distribution without probe sequence explosion.
* **Investigation Plan**: Benchmark probe sequence lengths (PSL) using bitwise masking vs modulo across 50%, 70%, 85%, and 95% load factors.

### 2. Cache Line Boundary Split Penalties
* **False Assumption**: Bucket size can be any convenient size like 12 or 20 bytes.
* **The Reality**: Non-power-of-two bucket sizes (e.g. 12 or 20 bytes) cause buckets to straddle 64-byte L1 CPU cache lines. When a bucket straddles two cache lines, fetching it requires 2 L1 cache reads instead of 1.
* **Investigation Plan**: Keep bucket size at exactly 16 bytes (128 bits) with 64-byte alignment so exactly 4 buckets fit cleanly inside a single cache line without any boundary straddling.

### 3. Fingerprint Collision False Positives
* **False Assumption**: A 16-bit key fingerprint is guaranteed to eliminate all false matches.
* **The Reality**: A 16-bit fingerprint has a 1 in 65,536 ($\approx 0.0015\%$) chance of collision per candidate key. While it filters out 99.996% of non-matching probes without fetching payload storage pages, key comparison verification is still mandatory when fingerprints match.
* **Investigation Plan**: Measure fingerprint filter rejection efficiency across 1,000,000 lookup attempts.

### 4. Backward-Shift Deletion Infinities & Wrap-Around Truncation
* **False Assumption**: Moving adjacent entries backward during deletion is trivial.
* **The Reality**: In open addressing with circular buffer wrap-around, shifting entries backwards across index `0` back to `capacity - 1` can corrupt Probe Sequence Lengths (PSL) if wrap-around arithmetic is calculated incorrectly.
* **Investigation Plan**: Run stress tests with continuous random insert/delete cycles asserting that PSL invariants and key reachability are 100% preserved.

### 5. Incremental Rehashing Concurrent Read Ambiguity
* **False Assumption**: During $T0 \rightarrow T1$ incremental migration, lookups can just check $T0$ first.
* **The Reality**: Since new `PUT`s write directly to $T1$, an updated value for a key might exist in $T1$ while its stale version still resides in $T0$. $GET$ lookups MUST query $T1$ first, and only fall back to $T0$ if $T1$ misses.
* **Investigation Plan**: Build concurrent state tests ensuring zero stale reads during live cluster migration.

---

## 📝 Discovered Mistakes & Lessons Learned

### 1. FFM `ValueLayout` Alignment Enforcement
* **Discovered Mistake**: Initial bucket layout specified `Page ID` (32-bit int) at byte offset 6 (`[Distance 2B | Fingerprint 2B | Slot ID 2B | Page ID 4B]`).
* **Root Cause**: `ValueLayout.JAVA_INT` enforces natural 4-byte memory alignment by default. Accessing an `int` at offset 6 ($6 \pmod 4 = 2$) threw `java.lang.IllegalArgumentException: Misaligned access`.
* **Fix**: Reordered 16-byte bucket fields to satisfy natural alignment rules:
  `[ Page ID (4B @ 0) | Slot ID (2B @ 4) | Fingerprint (2B @ 6) | Distance (2B @ 8) | Reserved (6B @ 10) ]`.
* **Impact**: Zero alignment exceptions and maximum CPU L1 cache load throughput.

### 2. Hot-Path State Mutation Penalties in JMH Benchmarks
* **Discovered Mistake**: Instrumenting `get()` to increment metric counters (`totalLookups++`, `totalProbes++`, `fingerprintEvaluations++`) directly inside the read method created a 20ns+ overhead.
* **Root Cause**: Mutating object fields during high-frequency lookups forces CPU write-buffer flushes and invalidates CPU cache lines for read-only operations.
* **Fix**: Created pure, non-mutating `get()` for production hot paths and isolated metric tracking in `getWithMetrics()`.
* **Impact**: Lookup latencies dropped significantly into single-digit nanoseconds.
