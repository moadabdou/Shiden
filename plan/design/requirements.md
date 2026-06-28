# Market Requirements Document (MRD): Distributed In-Memory Data Grid (IMDG)

This document outlines the standard industry expectations and market requirements for a production-grade Distributed In-Memory Data Grid (IMDG) like **Shiden**. These requirements are modeled after enterprise solutions such as Hazelcast, Apache Ignite, Redis Enterprise, and Oracle Coherence.

---

## 1. Performance & Latency (The Baseline)

An IMDG is selected primarily for speed. Any solution must meet these performance characteristics:

* **Sub-Millisecond p99 Latency:** Reads and writes must execute in $< 1\text{ ms}$ under high load.
* **Garbage Collection (GC) Isolation:** Enterprise Java-based grids must isolate operational data from the JVM heap (via off-heap memory) to guarantee that Stop-The-World (STW) pauses do not impact clients.
* **Lock-Free / Low-Contention Concurrency:** Internal storage engines must utilize lock-free or fine-grained partition-level locking to maximize CPU core utilization.

---

## 2. High Availability & Fault Tolerance

Enterprises run IMDGs to ensure system uptime. The grid must remain online even during physical hardware or network failures.

* **Dynamic Sharding & Partitioning:** Data must be distributed across nodes automatically using consistent hashing or partition tables.
* **Configurable Replication Factor ($R$):** Users must be able to configure how many backup copies of each data partition exist in the cluster.
* **Split-Brain Protection (Quorum):** During a network partition, the grid must enforce quorum rules (e.g., Raft consensus) to prevent two isolated sub-clusters from accepting conflicting writes.
* **Dynamic Cluster Membership:** The grid must allow nodes to join or leave the cluster on the fly, triggering automatic partition rebalancing without manual intervention or downtime.

---

## 3. Data Consistency & Transactions

Depending on the use case, users require different levels of consistency.

* **Tunable Consistency:** 
  * *AP Mode (Availability/Partition-tolerance):* Fast, asynchronous replication where reads might return stale data (eventual consistency).
  * *CP Mode (Consistency/Partition-tolerance):* Strict, synchronous replication using consensus protocols (like Raft) where writes are only acknowledged once a quorum persists them.
* **Distributed Transactions:** Support for multi-key transactions across different nodes (typically implemented using Two-Phase Commit (2PC) or Saga patterns).
* **Entry-Level Locking:** Support for pessimistic (distributed locks) and optimistic (Compare-And-Swap / versioning) concurrency control.

---

## 4. Memory Management & Persistence

RAM is expensive and volatile. Production grids must manage it efficiently and protect against total power loss.

* **Off-Heap Storage Engine:** Storing keys, values, and metadata in native OS memory to bypass JVM Heap limits.
* **Persistence Options:**
  * **Write-Ahead Log (WAL):** Appending transactions to disk sequentially for crash recovery.
  * **Snapshotting:** Periodic background saves of the entire database state to disk.
  * **Cache-Through Patterns:** Read-through, write-through, or write-behind integration with persistent databases (RDBMS/NoSQL).
* **Eviction & Expiration Policy:** Support for Time-to-Live (TTL), Idle Time (Max Idle), and eviction algorithms like Least Recently Used (LRU) or Least Frequently Used (LFU) when native memory limits are hit.

---

## 5. Security & Compliance

Enterprise deployments require strict control over data access and transit.

* **Transport Layer Security (TLS):** Encryption for both client-to-node (North-South) and node-to-node (East-West) network traffic.
* **Authentication & Authorization:**
  * Client authentication (e.g., mTLS, SASL, or token-based).
  * Role-Based Access Control (RBAC) to restrict access to specific maps, caches, or administration commands.
* **Audit Logging:** Secure logs recording cluster state changes, configuration updates, and administrative access.

---

## 6. Client Support & Integrations

An IMDG is only as useful as the applications that can connect to it.

* **Multi-Language Clients:** Native drivers for key enterprise languages (Java, Go, C#, C++, Python, Node.js).
* **Standard Integration Interfaces:**
  * JCache (JSR-107) compliance for Java environments.
  * Hibernate Second-Level (L2) Cache integration.
  * Spring Cache / Spring Boot Auto-configuration.
* **Kubernetes Native Deployment:** Custom Operators for automated scaling, rolling upgrades, and lifecycle management on Kubernetes.
