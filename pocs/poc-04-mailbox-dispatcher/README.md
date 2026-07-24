# PoC-04: Thread-per-Core MPSC Mailbox Dispatcher & NUMA Affinity

This Proof of Concept (PoC) implements Shiden's high-performance, lock-free **Multi-Producer Single-Consumer (MPSC) Mailbox Dispatcher** and **Thread-per-Core Execution Kernel (RFC-004, RFC-003)**.

---

## 📂 File Structure

* `theory.md`: Theoretical foundations of Thread-per-Core execution, lock-free MPSC sequence math, cache-line isolation, and NUMA memory affinity.
* `mistakes.md`: Log of false sharing traps, heap allocation pitfalls, idle consumer spinning, and NUMA misalignments.
* `pom.xml`: *(To be generated during code execution phase)*
* `src/`: *(To be populated during implementation phase)*

---

## 🎯 Primary Engineering Goals & Hypotheses

1. **Sub-100ns Lock-Free Enqueue**: MPSC RingBuffers will sustain $< 100\text{ ns}$ enqueue latency under 16 concurrent producer event loops without lock contention.
2. **Zero JVM Heap Allocations**: Enqueue and drain pipelines execute with **0 Bytes** allocated on the JVM Heap per dispatched command.
3. **NUMA Interconnect Penalty Isolation**: Thread affinity core pinning (`sched_setaffinity`) and local NUMA socket memory allocation eliminate cross-socket interconnect bus penalties ($+2.5\times$ latency tax).
4. **Cooperative Multitasking**: Single-threaded worker threads executing 50µs time-sliced loops maintain flat p99.9 tail latency ($< 10\,\mu\text{s}$) while evaluating background index maintenance cycles.

---

## 🎓 Target Graduation Criteria

To graduate from PoC-04, the dispatcher must satisfy:
* **Enqueue Latency**: $< 100\text{ ns}$ per command under 16 concurrent producer threads.
* **Consumer Drain Throughput**: $> 50,000,000\text{ commands/sec}$ on worker thread.
* **Heap Garbage Impact**: **0 Bytes** allocated per dispatched command.
* **Tail Latency Stability**: p99.9 queue delivery delay $< 10\,\mu\text{s}$.
