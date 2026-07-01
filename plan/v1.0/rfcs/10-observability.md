# RFC-010: Observability & Operations

**Depends on:** RFC-003 (Networking), RFC-004 (Execution Model), RFC-005 (Membership)

## 1. Abstract
A distributed data grid is a black box without comprehensive observability. This RFC outlines the Operational Layer of Shiden, defining the non-blocking metrics collection, distributed tracing hooks, administrative HTTP APIs, and health endpoints required to run Shiden safely in production orchestrators like Kubernetes.

## 2. Background & Goals
Collecting metrics in a highly concurrent system can accidentally introduce the very locks and cache-contention we worked so hard to avoid.
* **In-Scope Goals:**
  * Define Lock-Free Metrics Collection.
  * Establish Prometheus metrics and OpenTelemetry tracing integration.
  * Define the HTTP Admin API and Kubernetes-native Health probes.
  * Specify logging and profiling mechanisms.
* **Out-of-Scope:**
  * Building a custom metrics dashboard (Grafana will be used).

## 3. Lock-Free Metrics Collection

If 16 Worker Threads constantly update a single shared `AtomicLong` for `total_requests`, the CPU bus becomes overwhelmed with cache-invalidation traffic (False Sharing). 

To preserve the Shared-Nothing architecture (RFC-004), metrics collection must be strictly decentralized.
1. **Thread-Local Counters:** Every Worker Thread and Netty EventLoop maintains its own private, cache-line padded `Metrics` struct.
2. **Zero-Contention Updates:** Because only the owning thread writes to its struct, it uses standard `long` variables, entirely avoiding expensive `CAS` or `Atomic` instructions on the hot path.
3. **The Scrape Thread:** A dedicated background `Observability Thread` periodically wakes up (e.g., every 1 second), reads the variables from all Thread-Local structs, aggregates them, and updates the global registry exposed to Prometheus. 

## 4. Exposed Metrics (Prometheus)

Shiden exposes a `/metrics` HTTP endpoint in the standard Prometheus plaintext format. Critical metrics include:

* **Storage (RFC-001 / RFC-008):** `arena_bytes_allocated`, `arena_bytes_free`, `pages_evacuated_total`, `huge_pages_active`.
* **Index Health (RFC-002):** `hash_index_capacity`, `hash_index_probe_distance_max`, `hash_index_rehash_events`.
* **Network & Execution (RFC-003 / RFC-004):** `network_connections_active`, `mailbox_queue_depth`, `command_latency_microseconds_bucket`.
* **Cluster & Replication (RFC-005 / RFC-006):** `swim_incarnation_number`, `cluster_topology_version`, `replication_lag_rsn`.
* **Persistence (RFC-007):** `wal_fsync_latency_microseconds`, `snapshot_duration_seconds`.

## 5. Distributed Tracing (OpenTelemetry)

Because Shiden processes requests asynchronously via MPSC queues and multiplexed Request IDs, standard timing logs are insufficient for debugging slow requests.
* **Traceparent Injection:** The Versioned Binary Protocol (RFC-003) reserves space in its Flags/Payload for an optional W3C `traceparent` header.
* **Span Propagation:** When a traceparent is detected, the Netty EventLoop opens a Span. The trace ID is passed within the `CommandContext` to the Partition Thread, and eventually to the Replicas. This allows operators to visualize a single request traveling from the Client $\rightarrow$ Netty $\rightarrow$ Partition Thread $\rightarrow$ Replication Queue $\rightarrow$ Replica Node.

## 6. Logging & Profiling

### 6.1. Asynchronous Logging
Writing logs to `stdout` or a file is a blocking I/O operation. If a Worker Thread logs an event, it could stall for milliseconds. 
* Shiden mandates the use of a lock-free asynchronous logger (e.g., Log4j2 with the LMAX Disruptor). Threads drop log messages into a ring buffer, and a background thread formats and flushes them to disk.

### 6.2. Continuous Profiling (JFR)
Shiden natively supports Java Flight Recorder (JFR) for low-overhead ($< 1\%$) continuous production profiling.
* Operators can dump a JFR recording via the Admin API to instantly analyze CPU flamegraphs, memory allocation hotspots, or lock contention without restarting the node.

## 7. Admin API & Health Endpoints

Because the primary port (e.g., `8080`) is dedicated exclusively to the highly optimized Binary Protocol (RFC-003), Shiden exposes a secondary port (e.g., `8081`) running a lightweight HTTP server strictly for administration.

### 7.1. Kubernetes Health Probes
* **`GET /health/liveness`:** Returns `HTTP 200 OK` as long as the JVM process is responsive and the internal EventLoops are not deadlocked. Used by orchestrators to restart frozen pods.
* **`GET /health/readiness`:** Returns `HTTP 200 OK` only if the node's SWIM status is `NORMAL` (RFC-005). If the node is `JOINING` (syncing its WAL/Snapshot) or `LEAVING` (draining partitions), it returns `HTTP 503`, instructing Kubernetes to stop routing client traffic to it.

### 7.2. Administrative Actions
* **`GET /admin/topology`:** Returns a JSON representation of the Consistent Hash Ring, current Topology Version, and the Node's active Partition Ownership map.
* **`POST /admin/snapshot`:** Triggers a manual, immediate In-Memory Shadow snapshot (RFC-007) across all owned partitions.
* **`POST /admin/jfr/dump`:** Generates and downloads a `.jfr` profiling file.

## 8. Guarantees
This RFC establishes the following architectural guarantees:
* **Zero Hot-Path Contention:** Metrics collection uses strictly Thread-Local structs, generating zero cache-invalidation or atomic locking overhead on the data path.
* **Non-Blocking Observability:** Asynchronous logging and background metric scraping ensure that observability never introduces latency spikes.
* **Orchestrator Ready:** Explicit Liveness and Readiness HTTP probes allow Shiden to integrate natively into Kubernetes rollout and failover lifecycles.
