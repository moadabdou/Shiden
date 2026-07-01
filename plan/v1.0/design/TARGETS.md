# Shiden Performance Budget & Targets

Every micro-benchmark, load test, and profiling session during Phase 4 (Prototyping & Validation) must answer one fundamental question: **Are we still on target?**

If a benchmark misses these targets, the prototype implementation must be refined.

## Non-Goals
The following metrics are explicitly **NOT** optimization targets:
* Maximum throughput at the expense of tail latency.
* Single-thread benchmark records.
* Synthetic benchmark wins.
* Optimizations that cause regressions in production p99 latency.

## Latency
*Assumptions: 10GbE Network, Hot Cache (no page faults), Local Node Owner, No heavy contention.*

* **GET:** 
  * Median: `< 50 µs`
  * P99: `< 200 µs`
* **PUT (Async Replication, Single Replica):** 
  * Median: `< 100 µs`
  * P99: `< 300 µs`
* **PUT (Sync Quorum W=2, Local Network):** 
  * Median: `< 500 µs`
  * P99: `< 800 µs`
* **Snapshot Freeze Pause (Pointer Swap):** 
  * Max: `< 50 µs`

## Throughput
* **MPSC Mailbox Enqueue:** 
  * Median: `> 50 million ops/sec`
* **Hash Index Lookup:** 
  * Median: `< 40 ns`
  * P99: `< 80 ns`
  * Max Probe Distance: `< 16`

## System Operations
* **Replication Lag:** `< 5 ms`
* **Cluster Failover Promotion:** `< 2 sec`
* **Crash Recovery Replay (512 GB state):** `< 30 sec`

## JVM / System Constraints
* **GC Allocation:** `Zero allocation on the hot path (steady-state).`
* **Memory Overhead:** `< 15% (Actual data vs total allocated capacity).`
* **TLB Misses / Page Faults:** Minimal (Guaranteed via `MAP_HUGETLB` and explicit `mbind` NUMA affinity).
