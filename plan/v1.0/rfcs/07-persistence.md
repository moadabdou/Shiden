# RFC-007: Persistence (WAL & Snapshots)

## 1. Abstract
While Shiden is primarily an In-Memory Data Grid (IMDG), relying solely on network replication for durability is catastrophic in the event of a datacenter power failure. This RFC outlines the persistence layer, detailing how Shiden unifies replication and persistence streams into an Append-Only Write-Ahead Log (WAL) and Point-In-Time Snapshots to guarantee crash consistency without blocking lock-free execution.

## 2. Background & Goals
Writing to disk is orders of magnitude slower than writing to RAM. The fundamental challenge of this RFC is ensuring that disk I/O does not destroy the sub-millisecond latencies established in RFC-003 and RFC-004.

* **In-Scope Goals:**
  * Unify the Replication Stream and Persistence Log.
  * Define the segmented Write-Ahead Log (WAL) layout and durability acknowledgment pipeline.
  * Define the non-blocking "In-Memory Shadowing" snapshot mechanism.
  * Detail crash recovery, format headers, and torn-write detection (`CRC32C`).
* **Out-of-Scope:**
  * Distributed backup to S3/Cold Storage.

## 3. The Canonical Mutation Log
At this architectural stage, there are multiple "logs" (Client requests, Replication streams, Disk WALs). Shiden unifies these conceptually.
* The Partition Thread produces a single **RSN-ordered mutation log**, which acts as the canonical representation of all state changes.
* Replication (RFC-006) and Persistence (RFC-007) are simply two independent, asynchronous consumers of this single stream.

## 4. The Write-Ahead Log (WAL)

### 4.1. Partition-Level Isolation & Segmentation
Just like replication, persistence is scoped to the **Logical Partition**. If a node owns 64 partitions, it maintains 64 isolated WAL sequences. This enables aggressive parallel recovery.
* **Rotation:** A WAL is not a single infinite file. It is heavily segmented (e.g., `wal-0001`, `wal-0002`). When a WAL segment reaches a threshold (e.g., 256MB), a new segment is created. Snapshot generation allows old segments to be cleanly deleted.

### 4.2. WAL Entry Format
The WAL persists the exact Binary Protocol Logical Commands, strictly ordered by their Replication Sequence Number (RSN).

`[ CRC32C (4) | RSN (8) | Request ID (8) | Partition ID (2) | OpCode (1) | Payload Length (4) | Payload Bytes ]`

* **Integrity (`CRC32C`):** Every entry is prefixed with a hardware-accelerated CRC32C checksum. If the server loses power mid-write, the torn write is instantly detected on reboot and the corrupted tail is discarded.
* **Safety Context:** The `Partition ID` is persisted to ensure a misplaced file is never replayed into the wrong partition. The `Request ID` is persisted to guarantee idempotency across crashes.
* **Future Hooks:** Support for WAL compression (LZ4/Zstd) and encryption are reserved as implementation-defined flags.

### 4.3. The Durability Acknowledgment Pipeline
The Partition Owner Thread (RFC-004) **never** blocks on a disk `fsync()`.
To achieve Synchronous (Always) durability, the acknowledgment pipeline requires explicit routing:
1. `Partition Thread` serializes the command into an off-heap `WAL Ring Buffer`.
2. Dedicated `Background I/O Thread` drains the buffer, writes, and calls `fsync()`.
3. The `I/O Thread` drops a success notification into a lock-free `Completion Queue`.
4. The `Partition Thread` polls the `Completion Queue` during its normal event loop and dispatches the final `ACK` back to the Netty EventLoop.

*Note: For Asynchronous durability, the `Partition Thread` bypasses the Completion Queue and sends the `ACK` instantly.*

## 5. Partition Snapshots

To prevent WALs from growing indefinitely, they must be compacted into Point-in-Time Snapshots. 

### 5.1. In-Memory Shadowing (Fork-less Snapshotting)
Because Java does not support safe OS `fork()` syscalls, Shiden uses **In-Memory Shadowing**.
1. **Trigger:** The active WAL segments reach a size threshold.
2. **Pointer Swap:** The Partition Thread allocates a new, empty Hash Index (RFC-002) and swaps its active pointer. All *new* client writes go to the new Hash Index and new FFM Arena pages.
3. **Dual Lookup:** During the snapshot phase, `GET` requests check the new Hash Index first; if not found, they check the frozen Hash Index.
4. **Serialization:** A Background I/O Thread slowly iterates the frozen Hash Index and streams the Key/Value pairs sequentially to disk.
5. **Cleanup:** Once the snapshot finishes, the frozen Hash Index is discarded, read lookups return to a single path, and all WAL segments older than the Snapshot's `Highest RSN` are deleted.

### 5.2. Snapshot File Format
To future-proof format evolution and prevent corrupted recoveries, Snapshots are heavily structured:

```text
Snapshot Header
  Magic Number (e.g., 0x5348534E "SHSN")
  Format Version
  Partition ID
  Topology Version
  Highest RSN
  Checksum (Whole-file CRC32C)
Data
  [ Key/Value Entries ]
Footer
```

## 6. Crash Recovery

When a node reboots after a crash, it must restore its state before rejoining the SWIM Gossip protocol. Because partitions are isolated, **all 64 partitions recover simultaneously in parallel.**

1. **Verify Integrity:** The node reads the latest `.snapshot` file and validates the `Whole-file CRC32C` checksum to guarantee it wasn't corrupted during a crash.
2. **Load Snapshot:** The Snapshot is loaded into the Off-Heap Arena. The engine notes the `Highest RSN` (e.g., `RSN = 120,000`).
3. **Replay WAL:** The node opens the WAL segments and seeks to `RSN 120,001`.
4. **Execution Pipeline:** Instead of a custom recovery code path, the WAL entries are deserialized and fed directly into the standard `Partition Thread Mailbox` to be executed exactly like live client requests.
5. **Cluster Catch-up:** The Partition transitions to `REPLAYING`, connects to the cluster, and requests any missing RSN commands from the current Primary that occurred while the node was offline.

## 7. Guarantees
This RFC establishes the following architectural guarantees:
* **Zero Execution Blocking:** The Partition Thread never blocks on disk I/O; durability ACKs are routed via lock-free Completion Queues.
* **Torn Write & Corruption Immunity:** Hardware-accelerated CRC32C checksums protect both granular WAL entries and massive Snapshot files.
* **Scalable Recovery:** Partition isolation allows $N$ partitions to recover fully in parallel.
* **Single Truth:** The RSN-ordered mutation log acts as the canonical source of truth for both Network Replication and Disk Persistence.
