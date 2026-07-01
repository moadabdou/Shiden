# RFC-009: Transactions & Atomic Commands

**Depends on:** RFC-001 (Storage Engine), RFC-003 (Networking), RFC-004 (Execution Model)

## 1. Abstract
This RFC defines how Shiden supports atomic operations, optimistic locking, and server-side scripting. By leveraging the lock-free Thread-per-Core architecture (RFC-004), Shiden can provide strict `SERIALIZABLE` isolation for transactions without the overhead of traditional database locks, mutexes, or Two-Phase Commit (2PC) protocols.

## 2. Background & Goals
Modern applications require the ability to mutate multiple records atomically (e.g., transferring funds between two wallets) or conditionally update records.
* **In-Scope Goals:**
  * Define the cross-partition constraint (Hash Tags).
  * Design the `MULTI` / `EXEC` transaction pipeline.
  * Establish Optimistic Concurrency Control (CAS and `WATCH`).
  * Define the Server-Side Lua scripting execution model.
* **Out-of-Scope:**
  * Distributed Two-Phase Commit (2PC) across multiple physical nodes.

## 3. The Single-Partition Constraint

Traditional relational databases use row-level locks and 2PC to execute transactions across multiple nodes. This requires massive network coordination, destroys throughput, and violates Shiden's lock-free execution model.

To maintain sub-millisecond latencies, Shiden enforces a strict **Single-Partition Constraint**.
* **Rule:** A transaction, CAS operation, or Lua script may only operate on keys that belong to the **same Logical Partition**.
* **Hash Tags:** To ensure multiple keys hash to the same partition, clients must use Hash Tags. If a key contains curly braces `{...}`, only the substring inside the braces is hashed.
  * `Hash("{user:123}:wallet") == Hash("{user:123}:inventory")`
  * Both keys are guaranteed to reside in the same Logical Partition, and therefore, are owned by the exact same Worker Thread.

## 4. Multi/Exec Transactions

Because of the Single-Partition constraint, executing a transaction in Shiden is trivially fast and requires absolutely no locks.

### 4.1. The Pipeline
1. **Buffering:** When a client sends `MULTI`, the Netty EventLoop (RFC-003) stops dispatching commands to the Partition and instead buffers them in a local list.
2. **Execution:** When the client sends `EXEC`, the EventLoop validates that all buffered keys belong to the same partition.
3. **Dispatch:** The EventLoop wraps the buffered commands into a single `TransactionBatch` object and enqueues it into the Partition Thread's MPSC Mailbox.
4. **Processing:** The Partition Thread polls the `TransactionBatch`. Because the thread is single-threaded and owns the partition exclusively, it simply executes the commands sequentially.
5. **Isolation:** No other client commands can interleave while the thread processes the batch. This guarantees strict **Serializable** isolation with zero locking overhead.

## 5. Optimistic Concurrency (CAS & WATCH)

Pessimistic locking (e.g., `SELECT FOR UPDATE`) forces threads to wait. Shiden uses Optimistic Concurrency Control (OCC).

### 5.1. Compare-And-Swap (CAS)
As defined in the Record Format (RFC-001), every record stores an internal 64-bit `Generation` (or Version) counter.
* A client fetches a record: `GET user:123` $\rightarrow$ returns `Value: "A", Generation: 42`.
* The client mutates the value locally and sends: `CAS user:123 42 "B"`.
* The Partition Thread checks if the current generation is still `42`. If yes, it updates the value and increments the generation to `43`. If no (another client mutated it), it rejects the command.

### 5.2. WATCH
`WATCH` allows clients to implement OCC across multiple keys.
1. Client sends `WATCH key_A key_B`.
2. The Partition Thread records the current `Generation` of those keys and associates them with the client's `Request ID` context.
3. If any other client mutates `key_A` or `key_B`, their generations increment.
4. When the watching client issues `EXEC`, the Partition Thread validates the generations. If they have changed, the transaction is instantly aborted.

## 6. Server-Side Lua Scripting

Complex atomic logic that requires branching (e.g., "GET key A, if > 10, then decrement A and increment B") cannot be pipelined easily without multiple network round trips. Shiden supports Server-Side Lua scripting to push logic to the data.

### 6.1. Thread-Local Runtimes
To avoid locks, Shiden instantiates a completely independent, lightweight Lua Runtime (e.g., GraalVM Polyglot or LuaJ) **inside every Worker Thread**.
* When a script is submitted (`EVAL`), it is enqueued to the Partition Thread just like a standard command.
* The Partition Thread executes the script synchronously.

### 6.2. Execution Constraints
Because the Partition Thread is executing Lua, it cannot process other network requests or perform maintenance (RFC-008). 
* **Timeout:** A strict execution timeout (e.g., 5ms) is enforced. If a Lua script exceeds this, the transaction is aborted. This prevents a bad `while(true)` script from stalling the partition and causing the SWIM protocol (RFC-005) to flag the node as `FAIL` due to missed heartbeats.
* **Determinism:** Scripts must be deterministic to ensure they yield the exact same results when replayed on Replicas (RFC-006) or during WAL crash recovery (RFC-007). Functions like `math.random()` or `os.time()` are either disabled or seeded deterministically by the Partition Thread.

## 7. Guarantees
This RFC establishes the following architectural guarantees:
* **Zero-Lock Transactions:** By enforcing the Single-Partition Constraint, transactions achieve `SERIALIZABLE` isolation naturally via the Thread-per-Core execution queue.
* **Deterministic Replay:** Transactions and Lua scripts mutate state deterministically, guaranteeing safe replication and WAL recovery.
* **Bounded Execution:** Lua scripts are strictly timed to protect the sub-millisecond latency guarantees and cluster heartbeat stability.
