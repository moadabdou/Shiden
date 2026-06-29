# Product Requirements Document (PRD): Shiden IMDG v1.0

## Core Objective
Deliver a high-throughput, low-latency Distributed In-Memory Data Grid operating purely in **AP (Availability/Partition-tolerance) mode**. The architecture focuses on off-heap memory management and zero-contention, cache-friendly networking.

---

## 1. Performance & I/O Architecture (The Baseline)
*(Industry Inspiration: **Redis** for Event-Loop and RESP protocol; **ScyllaDB** for Thread-per-core/Netty bindings)*

The system must be optimized for maximum throughput and minimal CPU cache invalidation.

* **Sub-Millisecond Latency:** Single-key GET and PUT operations must execute in $< 1\text{ ms}$ under load.
* **Non-Blocking I/O (NIO - The Fast Path):** All network communications must occur over raw TCP sockets using an asynchronous event-driven loop (e.g., Netty). The fast path handles accepting connections, reading bytes into off-heap memory, and RESP protocol parsing. HTTP/REST is out of scope.
* **Core Pinning & Cache Locality:** The networking layer must bind channels to fixed EventLoop threads. This keeps processing localized to specific CPU cores, maximizing L1/L2 cache hits and preventing thread context-switching overhead.
* **Virtual Threads (The Slow Path):** Blocking or slow tasks—like flushing an Append-Only File (AOF) to disk, taking a full memory snapshot, or waiting for a backup node to acknowledge a replication write—are handed off from Netty to an `ExecutorService` backed by Virtual Threads to prevent blocking the EventLoops.
* **Client Protocol (RESP):** The client-facing TCP server will implement the REdis Serialization Protocol (RESP). This allows developers to interact with Shiden immediately using existing tools like `redis-cli`, Jedis, or Lettuce, without needing a custom client SDK.
* **Internal Serialization (SBE):** For node-to-node replication and internal data structures, the system will use Simple Binary Encoding (SBE) to generate zero-allocation, ultra-fast byte arrays that map directly into off-heap `MemorySegment` buffers.

---

## 2. Off-Heap Memory Management
*(Industry Inspiration: **Hazelcast HDMD** (High-Density Memory Store) and **Apache Ignite**)*

To achieve predictable latencies, the grid must bypass standard JVM runtime garbage collection for user data.

* **Foreign Function & Memory (FFM) API:** All keys, values, and partition metadata must be stored directly in native OS memory (off-heap) using Java 21's FFM API, avoiding legacy JVM heap allocations.
* **Memory Isolation:** The JVM heap should only be used for the control plane (routing metadata and Netty execution frames), keeping the data plane entirely off-heap.
* **O(1) Eviction Engine:** When node capacity is reached, the system must evict data using a deterministic Least Recently Used (LRU) algorithm operating in constant $O(1)$ time, managed via a combined Hash Map and Doubly Linked List structure.

---

## 3. Sharding & Cluster Topology
*(Industry Inspiration: **Apache Cassandra** & **Redis Cluster** for Masterless Gossip & Consistent Hashing)*

The grid must distribute data effectively without relying on a centralized coordinator or heavy consensus protocols.

* **Consistent Hashing Ring:** Data distribution across nodes is managed via a consistent hashing ring. This ensures deterministic key placement and minimizes data movement when nodes join or leave the cluster.
* **Gossip Protocol (Membership):** Node discovery and cluster state propagation are handled via a lightweight UDP-based Gossip protocol, allowing nodes to dynamically update their routing tables.
* **Peer-to-Peer Routing:** Any node can accept a client request. If a node receives a key it does not own, it must transparently proxy the request to the correct owner node on the consistent hash ring and return the response to the client.

---

## 4. Consistency & Replication
*(Industry Inspiration: **Apache Cassandra** for AP-mode Eventual Consistency and Last-Write-Wins)*

This version prioritizes speed and availability over strict transactional safety.

* **AP Mode (Eventual Consistency):** Distributed transactions, Two-Phase Commit (2PC), and CP-mode consensus (Raft) are excluded from v1.0.
* **Asynchronous Replication ($R$):** The primary node for a partition writes the data to its off-heap memory, immediately acknowledges "Success" to the client, and replicates the write to backup nodes asynchronously.
* **Conflict Resolution:** Simple Last-Write-Wins (LWW) based on timestamp metadata will resolve concurrent write conflicts.

---

## 5. Persistence (Minimal Viable Safety)
*(Industry Inspiration: **Redis** for AOF and RDB Background Snapshotting)*

Basic safety nets are required to recover data after clean restarts or unexpected node failures.

* **Append-Only File (AOF):** Optional asynchronous logging of write operations to disk.
* **Snapshotting:** The capability to dump the current off-heap memory state to a binary file on disk for fast node recovery, executed in a background thread to prevent blocking active read/write operations.

---

## 6. Observability & Metrics
*(Industry Inspiration: **Prometheus JMX Exporter**)*

Sub-millisecond latency guarantees must be mathematically provable in production environments.

* **JMX Endpoints:** The system will expose critical internal metrics via Java Management Extensions (JMX).
* **Core Metrics Tracked:** 
  * **Latency:** p99 and p99.9 latencies for GET/PUT operations.
  * **Memory:** Total allocated off-heap bytes, memory fragmentation ratios, and eviction rates.
  * **Network:** Bytes in/out per second and current active client connections.
