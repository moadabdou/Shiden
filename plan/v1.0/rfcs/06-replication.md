# RFC-006: Replication & Failover

## 1. Abstract
This RFC defines how Shiden provides High Availability (HA) and data redundancy. It details the Primary-Replica architecture, replication transport mechanisms (Sync vs. Async), sequence ordering, and the automated failover state machine triggered when the membership protocol detects a failure. 

*Note: This RFC defines the mechanics of data movement. The authorization of failovers and the incrementing of Topology Versions require consensus, which is explicitly deferred to **RFC-008: Cluster Coordination & Topology Management**.*

## 2. Background & Goals
An in-memory data grid is useless if a single hardware crash causes catastrophic data loss.
* **In-Scope Goals:**
  * Define replication granularity (Partition-level vs. Node-level).
  * Establish Sequence Numbers to guarantee replication ordering.
  * Design the transport mechanism (Logical Command Shipping & Batches).
  * Establish consistency models (Async vs. Sync Quorum).
  * Define the lifecycle states of a Replica.
* **Out-of-Scope:**
  * Disk-backed persistence protocols (WAL / Snapshots to disk), to be covered in RFC-007.
  * Consensus algorithms for authorizing topology changes (RFC-008).

## 3. Partition-Level Replication

Replication does not happen at the "Node" level; it happens at the **Partition** level.

### 3.1. Primary & Replicas
For any given Topology Version, every Logical Partition has exactly:
* **One Primary (`OWNER`):** Handles all mutating client requests (`PUT`, `DEL`).
* **$N$ Replicas:** Maintain a redundant copy of the data. 

Because a Physical Node hosts many Logical Partitions (RFC-004), a single node acts as the Primary for some partitions and a Replica for others. This guarantees that if a node crashes, the failover load is distributed across the entire cluster rather than slamming a single "Standby" machine.

### 3.2. Replica Order and Priority
Replicas are not equal. Based on the Consistent Hash Ring, replicas have strict deterministic priority:
* `Replica 0` (Highest priority for promotion)
* `Replica 1`
* `Replica 2`

## 4. Replication Sequence Numbers

Without strict ordering, a `PUT` followed by a `DEL` could arrive at the replica as `DEL` then `PUT`, resulting in permanent data corruption.
Every Logical Partition maintains a strict monotonically increasing **Replication Sequence Number (RSN)**.

When the `OWNER` processes a command:
1. It increments the Partition's `RSN`.
2. It attaches the `RSN` to the command before shipping it.
3. Replicas process commands in strict `RSN` order and report their `Highest Applied RSN` back to the `OWNER` via ACK packets.

## 5. Consistency Models

Shiden allows operators (or individual client requests via Protocol Flags) to choose their trade-off between latency and durability. Future extensions may allow explicit read consistency levels (`ONE`, `QUORUM`, `ALL`).

### 5.1. Asynchronous Replication (AP - High Performance)
1. Primary writes to its local Hash Index & Arena.
2. Primary immediately sends `ACK` to the Client.
3. Primary dispatches the command to its Replicas in the background.
* **Trade-off:** Sub-millisecond latency, but a tiny window of data loss exists if the Primary crashes before the background dispatch completes.

### 5.2. Synchronous Quorum (CP - High Durability)
1. Primary writes locally.
2. Primary dispatches the command to Replicas.
3. Primary waits until it receives ACKs from a quorum of copies (e.g., $W = 2$ out of 3 total copies, which means the Owner + 1 Replica).
4. Primary sends `ACK` to the Client.
* **Trade-off:** Prevents data loss during failovers, but latency is bound by the network hop to the replicas.

## 6. Replication Transport Mechanism

How do bytes actually move from the Primary to the Replica?

### 6.1. Logical Command Shipping
Shiden does *not* replicate physical memory pages (which prevents heterogeneous hardware deployments). Instead, Shiden replicates **Logical Commands** (the Binary Protocol frames from RFC-003).
* **Batching Optimization:** The Primary's EventLoop batches multiple sequential commands into a single massive TCP payload, drastically reducing system call overhead.
* The Replicas execute the commands sequentially exactly as if they came from a client.

### 6.2. The Replication Lifecycle (State Machine)
A Replica transitions through multiple phases to guarantee consistency:
1. **`SYNCING` (Partition Snapshot Transfer):** If a Replica is brand new or fell too far behind, it requests a full point-in-time **Partition Snapshot** from the `OWNER` over TCP.
2. **`REPLAYING` (Catch-up Replay):** The Snapshot represents `RSN 120,000`. By the time the transfer finishes, the `OWNER` is at `RSN 125,000`. The Replica replays the missing $5,000$ buffered commands.
3. **`REPLICA` (Live Streaming):** The Replica is fully caught up and processes live streams of batched TCP commands.
4. **`OWNER`:** The Replica has been promoted due to a failover.

## 7. Failover & Replica Promotion

### 7.1. The Failure Trigger
1. As defined in RFC-005, the Membership layer declares that Node A has transitioned to `FAIL`.
2. The Cluster Coordination protocol (RFC-008) is notified of the death.

### 7.2. Promotion Execution
To prevent Split-Brain (two replicas promoting themselves), failover requires strict coordination:
1. The Coordination layer (RFC-008) authorizes the topology change and identifies the orphaned partitions.
2. The Coordination layer consults the Replica Priority list (e.g., `Replica 0`) and their `Highest Applied RSN`.
3. The chosen Replica is promoted from `REPLICA` to `OWNER`.
4. The global Topology Version advances.

### 7.3. Client Redirection & Idempotency
1. A client attempting to write to the dead Node A will experience a TCP timeout.
2. The client fetches the new Topology Version from any surviving node and redirects the failed `PUT` to the newly promoted `OWNER`.
3. **Idempotency:** Because the Client Request includes a unique 64-bit `Request ID` (RFC-003), the new `OWNER` can safely ignore the command if the dead Node A had successfully replicated it before dying.

## 8. Guarantees
This RFC establishes the following architectural guarantees:
* **Strict Ordering:** All replication is strictly ordered via Partition-level Sequence Numbers (RSN).
* **Distributed Failover Load:** Partition leadership is scattered; the death of one node distributes the recovery load smoothly across the entire cluster.
* **Flexible Consistency:** Clients can choose Async for speed or Sync for durability.
* **Hardware Agnostic Replication:** Using Logical Command Shipping instead of Physical Page Shipping ensures replicas can have completely different RAM/CPU architectures than the Primary.
