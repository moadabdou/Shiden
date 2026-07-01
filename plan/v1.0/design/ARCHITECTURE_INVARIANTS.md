# Shiden Architecture Invariants

This document serves as the constitution of the Shiden distributed database project. These invariants are non-negotiable. If a code change, PR, or implementation shortcut violates an invariant, it is an immediate architectural bug and must be rejected or redesigned.

## Ownership & Concurrency
1. **Thread-per-Core Constraint:** Exactly one OS thread owns a Logical Partition.
2. **Topology Constraint:** Exactly one physical node owns a Logical Partition for a given Topology Version.
3. **Isolation Constraint:** Only the Partition Thread mutates partition state.
4. **Lock-Free Hot Path:** There are absolutely no `synchronized` blocks or blocking locks inside the execution path.

## Memory & IO
5. **Shared-Nothing Memory:** Memory ownership strictly follows execution ownership. No off-heap pointer escapes its owner except through the reference-counted `SharedSegment` interface.
6. **Zero Blocking Syscalls:** The hot execution path performs zero blocking network or disk I/O.
7. **Zero Hot-Path Allocation:** There is zero object allocation on the steady-state execution path (no GC triggers).
8. **Memory Safety:** Every network packet is length-validated before touching off-heap memory.
9. **Cache-Line Alignment:** Mailbox routing SHALL use bounded, cache-line padded MPSC queues. Queues that allocate on enqueue or perform blocking synchronization are forbidden.

## Replication & State
10. **RSN Monotonicity:** Every mutation generates exactly one unique Replication Sequence Number (RSN). The RSN is strictly monotonic.
11. **Strict Ordering:** Replication, Persistence, and Execution strictly follow RSN ordering.
12. **Append-Only Disk:** The Write-Ahead Log (WAL) is strictly append-only.
13. **Transaction Bounds:** Transactions (MULTI/EXEC/Lua) never span multiple partitions.
14. **Replica Integrity:** Replicas never accept client write commands.

## Cluster Control
15. **Consensus Authority:** Topology changes originate only from the Raft Control Plane.
16. **Gossip Limitations:** SWIM never changes topology. SWIM only generates observations.
17. **Rolling Upgrades:** Topology changes are rejected if protocol compatibility (Min/Max Supported Versions) cannot be negotiated across the entire cluster.

## Time & Clocks
18. **Clock Independence:** Elapsed timers, heartbeats, and timeouts must rely exclusively on `System.nanoTime()`. The use of `System.currentTimeMillis()` is forbidden for duration calculations to prevent NTP drift bugs.

## Strict Execution & State
19. **Deterministic State Machine:** Given the same initial state and identical RSN-ordered mutation log, every OWNER and REPLICA shall converge to mathematically identical state.
20. **Canonical Mutation Stream:** Every state mutation enters the system exactly once through the Partition Thread and is assigned exactly one RSN. All replication, WAL, snapshots, and recovery mechanisms are derived solely from this single canonical stream.
21. **Shared State Prohibition:** Partition Threads shall never communicate through shared mutable memory (e.g., `ConcurrentHashMap`, `AtomicReference`). All inter-thread communication occurs exclusively through bounded MPSC message queues.
22. **Fail Fast:** If an invariant is violated (corrupt WAL, invalid RSN, impossible topology transition, checksum failure), the affected partition MUST instantly enter `FAIL` state. Silently catching errors and continuing execution is strictly forbidden.
