# Engineering Notebook: Mistakes, False Assumptions, & Pitfalls

This document logs the design flaws, incorrect assumptions, and performance bottlenecks discovered during the design, implementation, and benchmarking of the Off-Heap Arena Allocator.

---

## 🚫 Anticipated Pitfalls to Validate

### 1. The `WrongThreadException` Confined Arena Trap
* **False Assumption**: Creating the off-heap allocator with `Arena.ofConfined()` is always preferred because it guarantees single-threaded safety and is faster.
* **The Reality**: Under the hood, Netty event loops read data and pass it to Partition threads. If a snapshot is triggered (RFC-007), the Partition thread hands off the memory segments to a background persistence thread. Dereferencing a confined memory segment on a thread other than the creator thread throws a `WrongThreadException`.
* **Investigation Plan**: We will prototype allocator segments using both `ofConfined()` and `ofShared()` to measure the performance difference and verify correct multi-threaded handoffs without throwing exceptions.

### 2. Native Memory Leaks due to Unclosed Arenas
* **False Assumption**: The JVM will reclaim native memory if the Java objects representing the allocator are garbage collected.
* **The Reality**: While phantom/cleaner references can sometimes release direct byte buffers, the modern FFM API `Arena` does not automatically free native memory on GC unless it is specifically configured with an automatic lifecycle (which has high overhead). Explicit arenas must be closed using `.close()`. Failing to close them leads to native leaks that slowly exhaust OS RAM, resulting in OOM kills.
* **Investigation Plan**: Write a leak-detector test running millions of allocation cycles, validating RSS (Resident Set Size) memory stability on the OS, and verifying that the `Arena` is tied to a deterministic lifecycle.

### 3. Heap Pollution via Wrapper Objects on the Hot Path
* **False Assumption**: Using FFM means we have zero GC overhead.
* **The Reality**: If we read fields from a native memory segment by creating intermediate heap objects (e.g., calling `new Record(...)` or instantiating a helper layout class on every access), we pollute the JVM heap and trigger GC cycles.
* **Investigation Plan**: Ensure our API operates entirely on raw primitives and offsets (e.g., returning a `long` pointer or `int` offsets) rather than allocating wrapper objects on the hot read/write paths. Verify GC logs and JVM Flight Recorder (JFR) data to confirm that application-level heap allocations are avoided and FFM wrapper allocations are minimized.

### 4. Cache Line Bouncing and False Sharing on Free Lists
* **False Assumption**: Managing free blocks can be done with a simple global stack or queue.
* **The Reality**: If multiple threads attempt to allocate/deallocate slabs concurrently, they will compete for the same head pointer, causing cache line bouncing across cores. Even if threads allocate on separate partition pools, if the slab metadata tables are contiguous and sit on the same 64-byte L1 cache line, they will trigger false sharing.
* **Investigation Plan**: Ensure that our allocator isolates page/slab memory per Partition Thread (Shared-Nothing) and pads metadata fields to 64-byte boundaries.

### 5. Memory Alignment Penalties (Bus Errors & Speed Degradation)
* **False Assumption**: Bytes can be written and read from any arbitrary offset.
* **The Reality**: If a 64-bit `long` value is written at an odd offset (e.g., offset 3), the CPU must perform multiple memory accesses to fetch it. On some architectures (like ARM or SPARC), misaligned access triggers a SIGBUS hardware error; on x86, it silent-fails but introduces a significant latency penalty.
* **Investigation Plan**: Enforce a strict 8-byte alignment for primitive values and 16-byte alignment for entire records. Build validation tests that assert offsets are divisible by 8/16.

---

## 📝 Discovered Mistakes (Log)

*(To be populated during the implementation and benchmarking phase).*
