# Shiden Implementation Order

This document strictly defines the sequence of execution for Phase 4 (Prototype & Validation) and Phase 5 (Incremental Implementation). 

Building a distributed database requires a bottom-up approach. If the lowest-level data structures cannot hit their performance budgets, the upper-level network protocols will mathematically fail.

> **RULE:** No new RFCs may be drafted without explicit benchmark evidence proving that the current architecture fails to meet the `TARGETS.md` performance budget. Speculation ends here; profiling begins.

## Phase 4: Prototyping & Validation
1. **Robin Hood Hash Index (RFC-002)**
   - Validate cache-line alignment and `Max Probe Distance < 16`.
2. **FFM Arena Allocator (RFC-001 & RFC-008)**
   - Validate `MAP_HUGETLB` integration and zero-allocation Page/Slab hierarchy.
3. **Storage Pages & Slot Directories (RFC-001)**
   - Validate fast 16-byte record insertions and utilization tracking.
4. **MPSC Mailbox (RFC-004)**
   - Validate padded ring buffer throughput (`> 50M ops/sec`) using JMH.

## Phase 5: Incremental Implementation
5. **The Partition Thread (RFC-004)**
   - Implement the cooperative execution loop.
6. **Binary Protocol Parser (RFC-003)**
   - Zero-allocation Netty byte decoding.
7. **Single-Node KV Engine**
   - Combine Steps 1-6. Verify end-to-end `GET/PUT` latency.
8. **Persistence (RFC-007)**
   - Implement the WAL ring buffer, Background I/O thread, and Completion Queue.
9. **Replication (RFC-006)**
   - Implement RSN assignment and TCP command shipping.
10. **Membership & Gossip (RFC-005)**
    - Implement UDP heartbeat ping/pong.
11. **Cluster Coordination (RFC-011)**
    - Embed the Raft metadata protocol. Implement Topology Versioning and Replica Promotion.
12. **Transactions (RFC-009)**
    - Add MULTI/EXEC batching and Lua Script Sandboxing.
13. **Observability (RFC-010)**
    - Wire Thread-Local metrics into Prometheus.
