# RFC-005: Cluster Topology & Membership

## 1. Abstract
This RFC defines how isolated Shiden nodes (operating under the execution model defined in RFC-004) discover each other to form a decentralized, masterless in-memory data grid. By utilizing a Consistent Hashing Ring for data distribution and a SWIM-inspired Gossip protocol for failure detection and membership, the cluster scales horizontally without relying on a centralized coordinator.

## 2. Background & Goals
A distributed cache must seamlessly handle nodes joining, leaving, or crashing without halting the cluster.

* **In-Scope Goals:**
  * Define a SWIM-inspired membership protocol using direct/indirect probing.
  * Establish a Consistent Hashing ring using Virtual Nodes (vNodes).
  * Introduce Incarnation Numbers and Topology Versioning for monotonic state convergence.
  * Define the Routing layer that maps Logical Partitions to Physical Nodes.
* **Out-of-Scope:**
  * Data Replication and automatic Failover mechanisms (To be covered in RFC-006).

## 3. The Consistent Hashing Ring

Instead of mapping keys directly to physical nodes (which causes massive data reshuffling when a node crashes), Shiden uses a decoupled mapping strategy.

### 3.1. Logical Partitions (Slots)
As established in RFC-004, the entire keyspace is mathematically divided into a fixed number of **Logical Partitions** (e.g., `4096`).
* `PartitionID = xxHash3(Key) % 4096`
* **Every partition behaves exactly as described in RFC-004.** The physical node is merely a container that executes the partition's event loop.

### 3.2. Physical Node Ownership
The 4096 partitions must be distributed across the available physical nodes (e.g., Node A, Node B, Node C).
* Each Physical Node is assigned a unique 128-bit `Node ID`.
* **Virtual Nodes (vNodes):** To prevent uneven data distribution, each Physical Node is placed onto a 64-bit Consistent Hashing Ring multiple times (e.g., 256 vNodes per physical node).
* The 4096 logical partitions are assigned to the closest vNode on the ring.
* If Node A leaves the cluster, its partitions are smoothly redistributed among the remaining nodes rather than overwhelming a single neighbor.

## 4. Cluster Membership (SWIM Protocol)

Shiden implements a **SWIM-inspired membership protocol**. Membership updates are disseminated using epidemic gossip, while failure detection uses direct and indirect probes. 

### 4.1. Versioned Metadata & Incarnations
To prevent stale gossip packets from overwriting newer cluster state, all membership and routing metadata is strictly versioned. 

A Node's Membership Record consists of:
```text
NodeID
State (ALIVE, SUSPECT, LEAVING, LEFT)
Address (IP:Port)
Incarnation Number (e.g., 52)
```

The **Incarnation Number** is critical. If Node A's current incarnation is 52, and it receives a gossip packet claiming Node A is `SUSPECT` at incarnation 52, Node A simply increments its own incarnation to 53 and broadcasts `ALIVE`. The cluster immediately trusts the higher incarnation number and clears the suspicion.

### 4.2. Direct & Indirect Probing (Failure Detection)
Shiden does not rely on global consensus to detect failures.
1. **Direct Probe:** Node A randomly selects Node B and sends a UDP `PING`.
2. **Indirect Probe:** If Node B fails to `PONG` within the timeout, Node A suspects a network issue. Node A sends a `PING-REQ` to 3 random peers (Nodes C, D, E). 
3. **Relay:** Nodes C, D, and E send `PING` to Node B. If Node B responds to any of them, the `PONG` is relayed back to Node A.
4. **Suspicion:** If all indirect probes fail, Node A marks Node B as `SUSPECT` and gossips this state.
5. **Confirmation:** `FAIL` is declared after a configurable suspicion timeout, during which independent gossip observations may corroborate the failure, and no higher-incarnation `ALIVE` message is observed from Node B.

### 4.3. Anti-Entropy (Push/Pull)
Because UDP gossip limits payload size and packets drop, pure epidemic gossip can leave nodes with slightly divergent views. 
To guarantee convergence, Shiden employs periodic **Anti-Entropy over TCP**. Every $X$ seconds (e.g., 30s), a node establishes a TCP connection with a random peer and performs a full Push/Pull synchronization of the entire Membership and Topology tables.

## 5. Separating Membership from Routing

To maintain a clean architecture, Membership state is kept entirely separate from Partition Routing state.

**Membership State:** Tracks whether a physical machine is `ALIVE`, `LEAVING`, or `LEFT`.
**Routing State:** Tracks exactly what partitions a Node is responsible for.

Because partition migration takes time, Partition Ownership is not a simple boolean. A partition on a node can be:
* `OWNER`: Serving reads and writes.
* `MIGRATING`: Currently pushing data to a new node.
* `PENDING`: Awaiting inbound data from a migrating node.
* `REPLICA`: Serving read-only or backup traffic (RFC-006).

## 6. Joining & Leaving the Cluster

### 6.1. Bootstrap & Joining (Seed Nodes)
A new node cannot join the cluster out of thin air; it requires at least one **Seed Node**.
1. Node X boots and contacts the configured Seed Node.
2. Node X downloads the current Membership and Topology states.
3. Node X announces itself as `ALIVE` via Gossip.

### 6.2. Topology Versioning & Migration
When the cluster detects a new node, the topology must change. To prevent nodes from disagreeing on who owns what during the transition, Shiden uses a global **Topology Version**.
1. A topology update is generated (e.g., Topology Version 18).
2. Partition ownership is deterministically recalculated across the vNodes.
3. Partitions transition to `MIGRATING` and data transfer begins.
4. Once data transfer is complete, the Topology Version advances to 19, and the new owners transition to `OWNER`.

### 6.3. Graceful Leaving
Unexpected crashes result in `SUSPECT -> FAIL`. A deliberate shutdown avoids this:
1. Administrator issues a graceful shutdown.
2. Node transitions to `LEAVING` (gossiped).
3. The cluster recalculates topology and initiates `MIGRATING`.
4. Once all data is drained, the node broadcasts `LEFT` and terminates.

## 7. The Cluster Router (Bridging RFC-003 and RFC-005)

When a client sends `PUT user:123` to Node A, the Netty EventLoop (RFC-003) consults the **Cluster Router**:
1. `Hash = xxHash3(user:123)`
2. `Partition = Hash % 4096`
3. `Target Node = RoutingTable.getOwner(Partition, CurrentTopologyVersion)`

* **If Local:** The command is dropped directly into the local lock-free MPSC Mailbox (RFC-004).
* **If Remote:** The node acts as a transparent proxy. It serializes the command using the internal Binary Protocol (RFC-003) and fires it over an asynchronous TCP connection to the Target Node. When the target replies, the response is proxied back to the client.

## 8. Assumptions & Guarantees
This RFC operates under the following assumptions and guarantees:
* **Eventual Connectivity:** The cluster is eventually connected (there are no permanent network partitions).
* **UDP Tolerance:** UDP packet loss is completely tolerated through repeated epidemic gossip exchanges and TCP Anti-Entropy sweeps.
* **Monotonic Convergence:** Because membership metadata is versioned using Incarnation Numbers, newer information monotonically replaces older information, guaranteeing log(N) convergence.
* **Randomized Selection:** Gossip peer selection is randomized to prevent routing loops.
* **Partition Independence:** A physical node is merely a container. A migrated partition operates identically on any node in the cluster.
