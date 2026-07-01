# RFC-011: Cluster Coordination & Topology Management

**Depends on:** RFC-005 (Membership), RFC-006 (Replication)

## 1. Abstract
This RFC defines the **Control Plane** of Shiden. While RFC-005 dictates how nodes detect failures, this RFC dictates how the cluster *agrees* on the consequences of those failures. By implementing a strict consensus protocol (Raft) exclusively for cluster metadata, Shiden guarantees a single global source of truth for Topology Versions, safely authorizing replica promotions and preventing split-brain data corruption.

## 2. Background & Goals
A fundamental rule of distributed systems is that Failure Detection $\neq$ Consensus. If a network switch fails, severing a 5-node cluster into a 3-node partition and a 2-node partition, both sides will use SWIM gossip to observe the other side failing. Without a coordination layer, both sides will promote replicas to `OWNER`, permanently corrupting the data.

* **In-Scope Goals:**
  * Define the Raft-based Metadata Control Plane.
  * Establish deterministic Routing Table calculation.
  * Define Rolling Upgrade compatibility constraints.
  * Establish strict Clock Independence rules.
* **Out-of-Scope:**
  * Routing client data payloads through Raft (Client data is replicated via the high-throughput mechanisms in RFC-006).

## 3. The Metadata Control Plane

Shiden logically separates the cluster into a **Data Plane** (the Thread-per-Core partitions processing millions of requests) and a **Control Plane** (a low-throughput, highly consistent state machine).

### 3.1. Authority (SWIM vs Raft)
To remove all ambiguity:
* **SWIM never changes topology. SWIM only generates observations.** 
* **Raft is the sole authority that may change topology.**

### 3.2. Control Agents
Every Shiden binary is identical; there is no separate "Master Node" executable. 
* Every node runs a **Control Agent** connected to the Raft state machine.
* Operators can mark specific nodes as `CONTROL_ELIGIBLE` via configuration. Only these nodes participate in Raft Leader elections and vote in quorums. (Typically 3 or 5 nodes in a cluster are marked eligible).

### 3.3. The Topology Version (Raft Log Index)
To guarantee global uniqueness, the **Topology Version** is strictly defined as the **Raft Log Index** that generated the current state.
* Example: If the cluster state is updated by Raft Log entry #150, the global Topology Version is exactly `150`.

## 4. The Topology Change Lifecycle

When hardware fails, the cluster heals via a strictly serialized pipeline.

1. **Detection:** The SWIM Protocol (RFC-005) on Node B observes that Node A is unresponsive. 
2. **Proposal:** Node B submits a `NodeFailure(Node A)` proposal to the Raft Leader over a dedicated TCP control channel.
3. **Commit:** The Raft Leader appends this membership change intent to its Raft Log. It requires an acknowledgment from a **Majority Quorum** (e.g., 2 out of 3 eligible Control Nodes).
4. **Broadcast & Deterministic Calculation:** Once committed, the Topology Version increments (e.g., to `151`). The Raft Leader broadcasts the committed intent (`Remove Node A`) to all nodes.
5. **Execution:** Because the Consistent Hash Ring (RFC-005) is mathematically deterministic based on Node IDs, every node independently calculates the exact same new `Topology Map`. The nodes safely promote local partitions from `REPLICA` to `OWNER`.

*By replicating the Membership Intent rather than 4096 individual partition assignments, the Raft log remains microscopic and network traffic is vastly reduced.*

### 4.1. Raft Leadership Changes
If the Raft Leader itself crashes:
1. Control Nodes detect the missed Raft heartbeats.
2. An election is held among `CONTROL_ELIGIBLE` nodes.
3. A new Leader is established. 
4. The Topology Version remains unchanged until the new Leader commits a new proposal. The Data Plane continues serving client requests uninterrupted during the election.

## 5. Architectural Invariants for Coordination

### 5.1. Clock Independence
Distributed systems fail spectacularly when relying on wall-clocks. 
* **Invariant:** Nowhere in the Shiden implementation (Raft timeouts, SWIM heartbeats, Lua timeouts, TTLs) shall `System.currentTimeMillis()` be used to measure elapsed time.
* **Mandate:** All elapsed durations must exclusively use the monotonically increasing `System.nanoTime()` to survive NTP adjustments and leap seconds.

### 5.2. Rolling Upgrade Compatibility
When upgrading a cluster, Node A might run Shiden `v1.4` while Node B runs `v1.5`. 
* **Invariant:** Every node broadcasts a `Min_Supported_Protocol` and `Max_Supported_Protocol` value.
* **Mandate:** The Raft Leader calculates the highest mutually supported protocol. Topology changes (like a node joining) are strictly rejected if a mutually compatible protocol cannot be negotiated across the entire cluster.

## 6. Guarantees
This RFC establishes the following architectural guarantees:
* **Absolute Split-Brain Immunity:** Topology changes are serialized through a Raft quorum. A network minority is mathematically unable to commit a topology change.
* **Deterministic Routing:** Replicating intents instead of raw partition maps ensures all nodes derive the same routing tables.
* **Time Travel Immunity:** Monotonic nano-timers guarantee that leap seconds and NTP drift do not cause false leader elections.
