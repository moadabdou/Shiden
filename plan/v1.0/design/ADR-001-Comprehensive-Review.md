# ADR-001: Comprehensive Architecture Review (Phase 2.5 & 3)

**Date:** 2026-07-01
**Status:** `APPROVED`
**Scope:** RFC-001 through RFC-012

## 1. Executive Summary
The Shiden architectural blueprints were subjected to a rigorous, bottom-up Architectural Design Review (ADR) by the core engineering committee. The architecture is highly cohesive, relying on a strict Shared-Nothing, Thread-per-Core model. 

The review surfaced critical edge cases around FFM concurrency, split-brain topology, disk backpressure hysteresis, and clock independence. With the integration of RFC-011 (Cluster Coordination), these flaws have been resolved. The architecture is formally rated `10/10` and approved for the implementation phase.

---

## 2. Review Findings by Discipline

### 2.1. JVM Runtime Engineer (Focus: Memory & Execution)
* **Finding 1: The Confined Arena Trap**
  * *Context:* RFC-004 and RFC-008 specify that the Partition Thread exclusively owns the memory via `Arena.ofConfined()`.
  * *The Flaw:* RFC-007 (Persistence) states that during a snapshot, the Partition Thread freezes the Hash Index and hands it to an I/O Thread. A confined arena will throw `WrongThreadException`.
  * *Resolution:* Introduce a `SharedSegment` interface (`segment()`, `retain()`, `release()`) backed by `Arena.ofShared()`. The execution engine interacts solely with this abstraction, preventing raw MemorySegment leaks and safely allowing cross-thread snapshot handoffs via manual lifetime management.

### 2.2. Distributed Systems Engineer (Focus: Topology & Replication)
* **Finding 2: The Split-Brain Topology Race**
  * *Context:* RFC-005 relies on SWIM gossip to declare `FAIL`.
  * *The Flaw:* SWIM answers "Who appears alive?", not "Who is allowed to change the cluster?". In a network partition, both sides would use Gossip to promote replicas, permanently corrupting the data.
  * *Resolution:* Drafted **RFC-011**, explicitly segregating the Data Plane from the Control Plane. Topology is deterministically computed based strictly on intents committed to the Raft consensus log.

* **Finding 8: Rolling Upgrade Compatibility**
  * *The Flaw:* If nodes are deployed with differing binary protocol versions, data corruption could occur.
  * *Resolution:* Nodes must broadcast their `Minimum Supported Protocol` and `Maximum Supported Protocol`. Topology changes are rejected if protocol compatibility cannot be established via the Control Plane.

### 2.3. SRE / Production Engineer (Focus: Persistence & Ops)
* **Finding 3: Cascading Failure on Disk Full (Hysteresis Loop)**
  * *The Flaw:* If the SSD stalls, the WAL ring buffer fills, blocking the Partition Thread. If the Partition Thread blocks, SWIM heartbeats stop, causing a false `FAIL` state and a massive failover storm.
  * *Resolution:* Implement explicit TCP socket backpressure with Hysteresis. When the WAL queue reaches `90%` capacity, invoke `channel.config().setAutoRead(false)`. When it drains to `40%`, re-enable AutoRead. This prevents rapid toggling while protecting the Partition Thread from disk starvation.

* **Finding 7: Clock Independence**
  * *The Flaw:* Relying on `System.currentTimeMillis()` for heartbeats or TTLs will fail when NTP adjusts the wall clock or leap seconds occur.
  * *Resolution:* An absolute invariant has been added: All elapsed timers, heartbeats, and timeouts must rely exclusively on `System.nanoTime()`.

### 2.4. Performance Engineer (Focus: Threading & Locks)
* **Finding 4: MPSC False Sharing**
  * *The Flaw:* Multiple Netty EventLoops blasting the Partition Mailbox will cause heavy cache-line invalidation (False Sharing) on the queue's tail pointer.
  * *Resolution:* Enforced a strict invariant: The implementation **SHALL** use a bounded cache-line padded MPSC queue. Queue implementations that allocate on enqueue or perform blocking synchronization are explicitly forbidden.

### 2.5. Security Engineer (Focus: Transactions)
* **Finding 5: Sandbox Escape in Lua Runtimes**
  * *Context:* RFC-009 introduces Server-Side Lua scripting.
  * *The Flaw:* Without strict bounds, Lua could exploit the host machine via standard libraries or infinite recursion.
  * *Resolution:* The runtime must explicitly blacklist the `os`, `io`, `package`, and `debug` libraries. The runtime must also cap execution time, memory allocation, and recursion depth to prevent OOM / CPU stalling attacks.

---

## 3. Scorecard & Decisions

| Metric | Score | Notes |
| :--- | :--- | :--- |
| **Storage Engine** | 10/10 | Perfectly aligned 16-byte formats. |
| **Execution Model** | 10/10 | Flawless Thread-per-Core mailbox routing. |
| **Networking** | 9.5/10 | Binary Protocol. (Resolved Hysteresis gap). |
| **Membership & Replication** | 10/10 | SWIM optimized, strictly ordered via RSN. |
| **Coordination** | 10/10 | RFC-011 permanently resolves Split-Brain via Raft metadata plane. |
| **Architectural Coherence** | **10/10** | **IMPLEMENTATION READY.** |

### 4. Action Items Before Code
- All findings have been successfully translated into Architecture Invariants and incorporated into the RFC stack.
- Phase 2 & 3 are officially concluded. The project is cleared to enter Phase 4 (Prototyping & Benchmarking).
