# RFC-004: Partition Architecture & Execution Model

## 1. Abstract
This RFC outlines the Execution Model for a single Shiden server node. It serves as the critical bridge between the Networking Layer (RFC-003) and the future Cluster Topology (RFC-005). It defines how logical partitions are structured, how they map to physical CPU cores, and how a Shared-Nothing, Thread-per-Core architecture guarantees lock-free execution with strict NUMA awareness.

## 2. Background & Goals
In standard enterprise Java applications, a massive thread pool (e.g., 200 threads) competes for shared resources via locks. This causes severe context-switching overhead, CPU cache thrashing, and false sharing. Shiden completely abandons this model.

* **In-Scope Goals:**
  * Define the quantitative relationship between Logical Partitions and Hardware Threads.
  * Establish a Shared-Nothing Thread-per-Core execution model.
  * Enforce CPU Affinity (Core Pinning) to maximize L1/L2 cache hits.
  * Ensure NUMA-aware memory allocations.
* **Out-of-Scope:**
  * Distributed rebalancing of these partitions across nodes (RFC-005).

## 3. Logical Partitions vs. Hardware Threads

The architecture separates the concept of a **Data Partition** from a **CPU Thread**.

### 3.1. Logical Partitions (The Data Boundary)
The total keyspace is divided into a fixed, large number of **Logical Partitions** (e.g., `1024` or `4096`).
* A Partition is purely a data structure. It holds its own Hash Index (RFC-002), Eviction Index, Page Manager, and Arena (RFC-001).
* *Why so many?* A large fixed number allows seamless rebalancing when the cluster grows. (e.g., Moving Partition `42` to a new machine).

### 3.2. Worker Threads (The Execution Boundary)
The server boots a fixed number of **Worker Threads** strictly equal to the number of physical CPU cores allocated to Shiden (e.g., `16` cores = `16` Worker Threads).
* *Why not Thread-per-Partition?* Context switching 1024 active threads destroys CPU caches.

### 3.3. The Mapping
If a node has 1024 partitions and 16 cores, each Worker Thread exclusively owns exactly **64 Partitions**.
* The Worker Thread is the *only* thread in the JVM allowed to touch the memory of those 64 partitions.
* This fulfills the "Partition Ownership Model" discussed in previous RFCs.

## 4. Hardware Optimization

### 4.1. CPU Affinity (Core Pinning)
To ensure L1 and L2 CPU caches remain blazing hot, Worker Threads are pinned to specific hardware cores using libraries like **OpenHFT Java-Thread-Affinity**.
* If Worker Thread `3` owns Partitions `128-191`, it is permanently bound to CPU Core `3`. 
* The OS scheduler is prevented from migrating this thread to Core `4`, eliminating cache invalidation penalties.

### 4.2. NUMA Awareness
Modern servers (e.g., 64-core AMD EPYC processors) consist of multiple CPU sockets and NUMA (Non-Uniform Memory Access) nodes. If a thread on Socket A tries to read memory physically attached to Socket B, the request crosses the motherboard interconnect (Infinity Fabric / QPI), doubling latency.

* **NUMA-Local Allocation:** When Worker Thread `3` (pinned to NUMA Node 0) allocates its `Arena` pages for its 64 partitions via the FFM API, it strictly instructs the OS to allocate memory exclusively from the RAM physically attached to NUMA Node 0.

## 5. Message Passing & Mailboxes

In RFC-003, we established that Netty EventLoops drop requests into a lock-free queue. However, having an EventLoop drop commands into 1,024 different partition queues would require the Worker Thread to constantly poll 64 different queues.

### 5.1. The Thread Mailbox
Instead of a queue per partition, Shiden uses **One Mailbox per Worker Thread**.
* The Mailbox is a single, massive **MPSC (Multi-Producer-Single-Consumer) Queue** (powered by JCTools/Agrona).
* Netty EventLoops (the Producers) enqueue `CommandContext` objects into the Mailbox.

### 5.2. Internal Command Routing
When an EventLoop receives `PUT user:123`, the internal routing logic is:
1. `Hash = xxHash3(user:123)`
2. `PartitionID = Hash % 1024`
3. `TargetWorker = PartitionID % 16`
4. The EventLoop enqueues the Command directly into `Worker Thread [TargetWorker]`'s Mailbox.

## 6. The Execution Loop (Cooperative Multitasking)

Because there are no background threads for compaction or eviction (which would require locks), the Worker Thread must cooperatively multitask between serving network requests and performing memory maintenance.

The infinite loop of a Worker Thread looks like this:
1. **Poll Mailbox:** Drain up to $X$ commands (e.g., 128 commands) from the MPSC Mailbox.
2. **Execute:** For each command, inspect the `PartitionID` and route it to the specific local Partition data structure. Perform the Hash Index lookup and Page write.
3. **Dispatch Responses:** Hand the responses back to the Netty EventLoops via their respective MPSC Response Queues.
4. **Maintenance Cycle (Time-Bound):** 
   * Spend a maximum of $Y$ microseconds (e.g., 50µs) performing background tasks.
   * *Tasks:* Page Evacuation, In-Page Compaction, or Eviction (RFC-001/002).
5. **Yield / Repeat.**

## 7. Architecture Diagram

```text
               Netty EventLoops
             (Accept I/O, Decode)
      ┌───────────────┴───────────────┐
      │                               │
  [Mailbox 0]                     [Mailbox 1]       (MPSC Queues)
      │                               │
      ▼                               ▼
Worker Thread 0                 Worker Thread 1     (Pinned to CPU Cores)
 (CPU Core 0)                    (CPU Core 1)
      │                               │
      ├─ Partition 0                  ├─ Partition 64
      ├─ Partition 1                  ├─ Partition 65
      ├─ ...                          ├─ ...
      └─ Partition 63                 └─ Partition 127
            │                               │
       (NUMA Node 0)                   (NUMA Node 0)
```

## 8. Guarantees
This RFC establishes the following architectural guarantees:
* **Zero Contention:** A Partition's memory is mutated exclusively by one OS thread.
* **Cache Locality:** Threads are pinned to physical cores; memory is allocated NUMA-locally.
* **Deterministic Routing:** Keys explicitly map to a Partition, which explicitly maps to a Worker Thread.
* **No Background Threads:** All memory management (compaction/eviction) is handled cooperatively by the Worker Thread via bounded time-slices to prevent STW pauses.
