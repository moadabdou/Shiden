# RFC-003: Client Networking & Binary Protocol

## 1. Abstract
This RFC outlines the design of Shiden's Client Networking Layer. Before clustering or replication can exist, the system must have a highly optimized pathway to transport bytes from a client TCP socket down to the appropriate lock-free Partition Owner Thread. This layer leverages non-blocking I/O (Multi-Reactor pattern), a custom binary protocol, zero-copy buffer management, and asynchronous message passing to saturate 10Gbps+ networks with sub-millisecond latencies.

## 2. Background & Goals
A high-performance in-memory datastore is often bottlenecked by network I/O and protocol parsing rather than RAM lookups. 
* **In-Scope Goals:**
  * Implement an event-driven TCP server using optimal OS polling mechanisms.
  * Design a fast, easily parseable, versioned binary protocol.
  * Define the lock-free MPSC queues bridging network I/O to Partition Owner Threads.
  * Support aggressive request multiplexing (out-of-order responses).
* **Out-of-Scope:**
  * Node-to-node gossip and cluster topology (to be covered in RFC-005).

## 3. TCP Server Architecture (Multi-Reactor Pattern)

The networking layer follows the Multi-Reactor architecture. The initial implementation will use **Netty**, as it provides mature native OS transports and efficient off-heap buffer management.

### 3.1. Threading Model
The server uses the classic Multi-Reactor pattern:
1. **Boss Group (1 Thread):** Dedicated solely to accepting incoming TCP connections (`accept()`) and handing them off to the Worker Group.
2. **Worker Group (I/O Event Loops):** A pool of threads (typically equal to the number of CPU cores). These threads execute the event loop (e.g., `epoll_wait`), read bytes from active sockets, frame them, and decode them into Commands.

### 3.2. OS Abstraction
The architecture relies on the best transport available for the host OS:
* Linux: `epoll` via Edge-Triggered native transports.
* macOS: `kqueue`.
* Future: `io_uring`.

## 4. Binary Protocol & Request Framing

Parsing text-based protocols wastes CPU cycles. Shiden will use a custom **Versioned Binary Protocol**. *Note: The binary protocol is intentionally generic enough that it can later be reused for inter-node communication (RPC) during clustering.*

### 4.1. Protocol Versioning & Frame Header
To ensure forward compatibility, every frame begins with a versioned header. No CRC is included as TCP already guarantees stream correctness. The header is explicitly padded to 24 bytes for optimal CPU alignment.

* **Frame Header (24 Bytes):**
  * `Magic Number` (16-bit): Fixed identifier (e.g., `0x5348` "SH").
  * `Protocol Version` (8-bit): E.g., `0x01`.
  * `Frame Length` (32-bit): Total bytes in this message.
  * `Flags` (8-bit): Reserved for future protocol extensions (e.g., compression, tracing).
  * `Request ID` (64-bit): Unique identifier for multiplexing.
  * `OpCode` (8-bit): The command type (`0x00 = PING`, `0x01 = PUT`, `0x02 = GET`, `0x03 = DEL`).
  * `Reserved` (56-bit / 7 Bytes): Padding to ensure the header aligns to exactly 24 bytes.

### 4.2. OpCode-Specific Payloads
The payload structure depends strictly on the OpCode.
* **PUT Payload:** `Key Length` (16-bit) | `Key Bytes` | `Value Length` (32-bit) | `Value Bytes` | `Expiration Timestamp` (64-bit) | `Record Flags` (8-bit)
* **GET / DEL Payload:** `Key Length` (16-bit) | `Key Bytes`
* **PING Payload:** Empty (0 bytes). Used as an explicit heartbeat because OS-level TCP Keep-Alive timeouts are often too slow to reliably detect dead connections.

## 5. Buffer Management & Zero-Copy Pipeline

When bytes arrive at the NIC, the OS places them in a buffer. The networking layer wraps this direct OS memory in an off-heap `ByteBuf`.

### 5.1. Zero Parsing Allocation
The Decoder does not create Java `String` or `byte[]` objects. It slices the `ByteBuf` and constructs a lightweight `Command` context object.

**The Command Context Layout:**
```text
Command {
    RequestID (64-bit)
    OpCode (8-bit)
    ChannelContext (Reference to the Netty Channel / EventLoop)
    ByteBuf (Zero-copy payload pointer)
}
```

### 5.2. Buffer Lifetime & Ownership
To prevent Use-After-Free crashes in a zero-copy pipeline, buffer lifecycle must be strictly managed via Reference Counting:
1. **Decode:** The EventLoop decodes the frame and calls `retain()` on the `ByteBuf`.
2. **Queue:** The `Command` is enqueued to the Partition.
3. **Execute:** The Partition Owner Thread reads directly from the `ByteBuf`, copying the bytes into the Arena (RFC-001).
4. **Release:** The Partition Owner Thread calls `release()` on the `ByteBuf` to return it to the network allocator pool.

### 5.3. Response Ownership & Batch Flushing
The Partition does *not* possess network context and never allocates outgoing buffers.
1. When the Partition completes a request, it constructs a lightweight response structure.
2. It uses the `ChannelContext` (from the original `Command`) to enqueue the response back to the specific EventLoop that owns the socket.
3. The EventLoop allocates the outgoing `ByteBuf` and encodes the response.
4. **Batch Flushing Optimization:** EventLoops will batch multiple responses into a single `flush()` cycle to significantly reduce syscall overhead and improve throughput.

## 6. Asynchronous Request Multiplexing

Standard databases often suffer from sequential protocol bottlenecks (Request 1 must complete before Request 2).
* **Multiplexing via Request ID:** Because every frame contains a 64-bit `Request ID`, Shiden processes requests entirely asynchronously.
* **Out-of-Order Responses:** If Request A hits a slow path and Request B hits a fast path, Shiden can send Response B before Response A. The client uses the `Request ID` to match the response, eliminating sequential blocking.

## 7. The Handoff: Connecting Sockets to Partitions

EventLoops are intentionally stateless with respect to storage. All data ownership resides inside Partition Owner Threads.

### 7.1. Lock-Free MPSC Queues
Because multiple EventLoops (Network Workers) can receive requests destined for the same Partition, the input queue must be a **Multi-Producer-Single-Consumer (MPSC)** ring buffer. 
*(Note: To guarantee battle-tested correctness and performance, Shiden will not implement this from scratch. It will utilize an industry-standard lock-free library such as **JCTools (`MpscArrayQueue`)** or **Agrona**).*

1. The EventLoop hashes the Key using **`xxHash3-64`** (`xxHash3` provides extremely high throughput while maintaining excellent avalanche properties for partition distribution).
2. The EventLoop enqueues the command into the lock-free MPSC Input Queue of the Partition Owner Thread.

Similarly, multiple Partitions may need to send responses back to the same EventLoop concurrently. Thus, the Response Queue attached to the EventLoop is *also* an **MPSC Queue**.

### 7.2. Backpressure Mechanism
If a Partition Owner Thread falls behind, its input MPSC queue will fill up.
* **Pause:** When the EventLoop fails to enqueue a command (Queue Full), it calls `disableAutoRead()` on the client's TCP socket. This forces TCP buffer queues to back up into the OS kernel, naturally applying TCP Window backpressure across the network.
* **Resume:** Once the Partition drains the queue below a low watermark threshold (e.g., 50% capacity), the EventLoop calls `enableAutoRead()` to resume processing.

## 8. Architecture Diagram: The Request Lifecycle

```text
       Client (TCP Socket)
                │
   [OS Kernel TCP Buffers]
                │
                ▼
     EventLoop (Netty I/O Thread)
       ┌────────────────────┐
       │ 1. epoll_wait()    │
       │ 2. ByteBuf decoded │
       │ 3. retain() called │
       └────────────────────┘
                │
                ▼
         Partition Router (xxHash3-64)
                │
  [ Lock-Free MPSC Input Queue ]
                │
                ▼
      Partition Owner Thread
       ┌────────────────────┐
       │ 1. Poll command    │
       │ 2. Read/Write Page │
       │ 3. release() buffer│
       └────────────────────┘
                │
  [ Lock-Free MPSC Response Queue ]
                │
                ▼
     EventLoop (Netty I/O Thread)
       ┌────────────────────┐
       │ 1. Allocate buffer │
       │ 2. Encode Response │
       │ 3. Batch flush()   │
       └────────────────────┘
                │
                ▼
       Client (TCP Socket)
```

## 9. Guarantees
This RFC establishes the following architectural guarantees for Shiden:
* EventLoops never directly access storage pages.
* Partition Owner Threads are the sole owners of partition state and never access network sockets.
* All client requests and responses are routed through lock-free MPSC queues.
* The client protocol is versioned, 24-byte aligned, and forward compatible.
* Buffer ownership is explicitly managed through explicit reference counting (`retain()` / `release()`).
* All socket I/O is performed exclusively by EventLoop threads.
