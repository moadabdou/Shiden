# RFC-012: Configuration & Cluster Settings

**Depends on:** RFC-011 (Cluster Coordination)

## 1. Abstract
A distributed system with hard-coded parameters is impossible to operate. This RFC defines the unified Configuration Management layer for Shiden, detailing how settings are divided into static node-local properties and dynamic cluster-wide parameters synchronized via the Raft Control Plane.

## 2. Background & Goals
Administrators require the ability to tune the database for specific workloads (e.g., maximizing throughput vs minimizing memory usage) and update settings without causing rolling restarts.
* **In-Scope Goals:**
  * Separate Static Configuration from Dynamic Configuration.
  * Define the Raft-based dynamic update pipeline.
  * List critical runtime tunable parameters.
* **Out-of-Scope:**
  * Specific parsing libraries (e.g., Jackson YAML vs pure Properties).

## 3. Configuration Tiers

### 3.1. Static Node Configuration (Local)
Some properties define the physical identity and bounds of the node. These cannot change while the JVM is running. They are read from a local `shiden.yaml` file on boot.
* **Node Identity:** `node_id`, `bind_address`, `gossip_port`, `binary_port`, `raft_port`.
* **Hardware Bounds:** `arena_memory_limit_gb` (RFC-009), `worker_threads_count` (RFC-004), `numa_pinning_enabled` (RFC-009).
* **Role Flags:** `control_eligible` (RFC-011), `seed_nodes`.

*If a static property needs to change, the administrator must modify the YAML file and gracefully restart the node (triggering a LEAVE and JOIN).*

### 3.2. Dynamic Cluster Configuration (Global)
Other properties dictate how the cluster behaves. If Node A thinks the Replication Factor is 3, but Node B thinks it is 2, the system will fail. These properties are **Dynamic** and globally synchronized.
* **Replication & Persistence:** `replication_factor`, `wal_flush_policy` (SYNC/ASYNC), `snapshot_threshold_mb` (RFC-007).
* **Timeouts & Safety:** `swim_suspicion_timeout_ms` (RFC-005), `lua_script_timeout_ms` (RFC-009).
* **Data Eviction:** `max_memory_policy` (LRU/LFU/Reject) (RFC-009).

## 4. The Dynamic Update Lifecycle

Because Shiden guarantees a single source of truth for metadata (RFC-011), updating a global setting is treated exactly like updating the cluster topology.

1. **Admin Request:** An operator sends a `POST /admin/config` request to any node (e.g., `{"replication_factor": 4}`).
2. **Proxy:** The receiving node forwards the request to the Raft Leader.
3. **Commit:** The Raft Leader appends the `ConfigUpdate` intent to its Raft Log and requires a majority quorum acknowledgment.
4. **Broadcast:** Once committed, the Raft Leader broadcasts the new configuration state to all nodes.
5. **Hot Apply:** Nodes receive the committed config and immediately apply the changes (e.g., the Hash Ring begins rebalancing to satisfy the new Replication Factor, or the EventLoops adjust their WAL flush behaviors).

## 5. Guarantees
This RFC establishes the following architectural guarantees:
* **Configuration Consistency:** By routing dynamic configuration through Raft, it is mathematically impossible for the cluster to suffer from "split-brain" configuration drift.
* **Zero-Downtime Tuning:** Operators can drastically alter the behavior of the database (e.g., toggling WAL fsync policies under load) without restarting the JVMs.
