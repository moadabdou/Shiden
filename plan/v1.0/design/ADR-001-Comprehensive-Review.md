# ADR-001: Comprehensive Architecture Review (Phase 2.5 & 3)

**Date:** 2026-07-01
**Status:** `CONDITIONAL APPROVAL`
**Scope:** RFC-001 through RFC-010

## 1. Executive Summary
The Shiden architectural blueprints were subjected to a rigorous, bottom-up Architectural Design Review (ADR) by the core engineering committee. The architecture is highly cohesive, relying on a strict Shared-Nothing, Thread-per-Core model. 

However, the intersection of **Java's FFM API concurrency rules**, **disk I/O backpressure**, and **split-brain topology generation** revealed critical edge cases that must be addressed before the implementation phase begins.

---

## 2. Review Findings by Discipline

### 2.1. JVM Runtime Engineer (Focus: Memory & Execution)
* **Finding 1: The Confined Arena Trap (CRITICAL)**
  * *Context:* RFC-004 (Execution Model) and RFC-008 (Memory Management) specify that the Partition Thread exclusively owns the memory. In Java 21's FFM API, this implies using `Arena.ofConfined()`.
  * *The Flaw:* RFC-007 (Persistence) states that during a snapshot, the Partition Thread freezes the Hash Index and hands it to a *Background I/O Thread* to serialize. If the memory is allocated in a confined arena, the background thread will instantly throw a `WrongThreadException` upon dereference.
  * *Resolution:* The memory must be allocated using `Arena.ofShared()`, but wrapped in a custom single-writer abstraction. When snapshotting, the Partition Thread must execute an `acquire()` reference count bump before handing it to the I/O thread, then the I/O thread must `release()` it.

### 2.2. Distributed Systems Engineer (Focus: Topology & Replication)
* **Finding 2: The Split-Brain Topology Race (CRITICAL)**
  * *Context:* RFC-005 (Membership) relies on SWIM gossip to declare `FAIL`. RFC-006 (Replication) uses this to promote a replica.
  * *The Flaw:* A pure SWIM protocol cannot guarantee consensus. If a 5-node cluster is severed by a network switch failure (3 nodes on Side A, 2 nodes on Side B), both sides will independently declare the other side `FAIL`. Both sides will increment the Topology Version and promote local replicas to `OWNER`. Data will permanently diverge.
  * *Resolution:* Replica promotion and Topology Versioning **cannot** be driven by Gossip. We must introduce `RFC-011: Cluster Coordination` (Raft or Paxos layer) to establish a global control plane that authorizes promotions only when a majority quorum of the *entire physical cluster* is present.

### 2.3. SRE / Production Engineer (Focus: Persistence & Ops)
* **Finding 3: Cascading Failure on Disk Full (HIGH)**
  * *Context:* RFC-007 specifies that WAL `fsync` is handled by an I/O thread, dropping ACKs into a Completion Queue for the Partition Thread.
  * *The Flaw:* If the physical SSD fills up (or experiences massive latency spikes), the I/O Thread blocks. The Partition Thread will continue writing to the lock-free WAL Ring Buffer until it is full. At that point, the Partition Thread *must* block, which means it stops polling the network and stops responding to SWIM Heartbeats. The node will be incorrectly declared `FAIL` by the cluster, causing a massive failover storm.
  * *Resolution:* Implement strict backpressure. If the WAL Ring Buffer reaches 90% capacity, the Netty EventLoops must stop reading from client sockets (`channel.config().setAutoRead(false)`), protecting the Partition Thread from stalling.

### 2.4. Performance Engineer (Focus: Threading & Locks)
* **Finding 4: MPSC False Sharing (MEDIUM)**
  * *Context:* RFC-004 defines MPSC Mailboxes for the Partition Thread.
  * *The Flaw:* With multiple Netty EventLoops hammering a single Partition's MPSC queue during a burst, the queue's `tail` pointer will suffer from severe CPU cache-line invalidation (False Sharing) across NUMA nodes.
  * *Resolution:* Ensure the implementation exclusively uses padded, wait-free ring buffers (e.g., JCTools `MpscArrayQueue`), and batch commands at the EventLoop layer before offering them to the MPSC queue to reduce CAS instructions.

### 2.5. Security Engineer (Focus: Transactions)
* **Finding 5: Sandbox Escape in Lua Runtimes (HIGH)**
  * *Context:* RFC-009 introduces Server-Side Lua scripting inside the Partition Thread.
  * *The Flaw:* If the embedded Lua runtime (e.g., GraalVM) is not perfectly sandboxed, a malicious client script could execute `os.execute("rm -rf /")` or read the physical WAL files via standard library I/O.
  * *Resolution:* The Lua execution context must explicitly disable the `os`, `io`, and `package` standard libraries. Memory allocations within the script must be strictly capped to prevent OOM attacks.

### 2.6. Principal Architect (Focus: Architecture Invariants)
* **Finding 6: Invariant Verification (PASS)**
  * *Check:* "Exactly one thread owns a partition." (Maintained, pending the FFM Arena fix).
  * *Check:* "Replication, Persistence, and Execution strictly follow RSN ordering." (Maintained, nicely unified in RFC-007).
  * *Conclusion:* The macro-architecture is exceptionally sound. The layers cleanly abstract over each other.

---

## 3. Scorecard & Decisions

| Metric | Score | Notes |
| :--- | :--- | :--- |
| **Correctness** | 8/10 | Docked 2 points due to the lack of a Raft consensus layer for split-brain topology resolution. |
| **Performance** | 10/10 | The Thread-per-Core, Shared-Nothing, FFM huge-page design is state-of-the-art. |
| **Operability** | 9/10 | Excellent observability (RFC-010), but needs explicit disk backpressure mechanics. |
| **Security** | 7/10 | Docked for missing Lua sandbox constraints. |
| **Complexity** | 9/10 | Elegantly avoids Two-Phase Commit and Hazard Pointers. |
| **Extensibility** | 9/10 | Network and Storage protocols are versioned and flexible. |

### 4. Action Items Before Implementation
1. **[Architect]** Draft `RFC-011: Cluster Coordination` to introduce the Raft/Consensus layer for Topology state (Fixes Finding 2).
2. **[JVM Engineer]** Update RFC-008 to mandate `Arena.ofShared()` with reference counting for snapshot handoffs (Fixes Finding 1).
3. **[SRE]** Update RFC-003 and RFC-007 to explicitly define socket backpressure when the WAL buffer fills (Fixes Finding 3).
4. **[Security]** Update RFC-009 to mandate Lua standard library sandboxing (Fixes Finding 5).
