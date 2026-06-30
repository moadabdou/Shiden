RFC-004 — Partition Architecture (before Cluster)

This is the one I would add.

Because currently you have

Storage

↓

Hash Index

↓

???

↓

Network

↓

Cluster

What's missing is

How does a single server organize partitions?

For example

Machine

Partition 0

Partition 1

Partition 2

Partition 3

Questions like

How many partitions?
Thread-per-partition?
CPU affinity?
NUMA awareness?
Message passing?
Mailboxes?
Lock-free queues?
Partition ownership?
Command routing?

This RFC becomes the bridge between networking and clustering.

After that...

RFC-005 — Cluster Topology

Now introduce

Node A

Node B

Node C

Topics

Cluster membership
Consistent hashing
Slot ownership
Node IDs
Metadata
Joining
Leaving
Heartbeats
Failure detection

Notice something.

The cluster RFC can now simply say

"Every partition behaves exactly as described in RFC-004."

Very clean separation.

RFC-006 — Replication

Now that nodes exist

Leader

↓

Followers

Questions

Sync
Async
Write quorum
WAL shipping
Snapshot transfer
Replica promotion
RFC-007 — Persistence

Even if Shiden is primarily an IMDG, persistence deserves its own RFC.

Topics

WAL
Snapshot
Recovery
Checksums
Crash consistency
RFC-008 — Memory Management

I'd definitely dedicate an RFC to this because your project is heavily off-heap.

Topics

Page allocator
Slab allocator
Free lists
Defragmentation
Huge Pages
NUMA
Memory reclamation
RFC-009 — Transactions / Atomic Commands

If you plan to support

MULTI

WATCH

CAS

Lua

etc.
RFC-010 — Observability

Metrics

Tracing

Logging

Profiling

Admin API

Health endpoints

Another thing I'd change

I would avoid making the networking RFC talk about clustering.

Instead

RFC-003

Client

↓

Network

↓

Partition Router

The router simply asks

"Which partition owns this key?"

At this point it doesn't matter whether that partition is local or remote.

Later, the cluster RFC replaces

Local Router

with

Cluster Router

without changing the network layer.

This keeps your architecture extremely modular.

My proposed roadmap
RFC-001
Off-Heap Engine
        │
RFC-002
Off-Heap Hash Index
        │
RFC-003
Networking & Binary Protocol
        │
RFC-004
Partitioning & Execution Model
        │
RFC-005
Cluster Topology & Membership
        │
RFC-006
Replication & Failover
        │
RFC-007
Persistence (WAL / Snapshots)
        │
RFC-008
Memory Management
        │
RFC-009
Distributed Operations / Transactions
        │
RFC-010
Observability & Operations
One more suggestion

Since you've mentioned before that Shiden is intended to be a serious IMDG rather than just a cache, I'd also add a final "architecture" RFC near the end:

RFC-000 (or RFC-999): Overall System Architecture

This isn't written first—it is written last. Once every subsystem is designed, you produce a high-level architectural RFC with diagrams showing how they all fit together:

               Client
                  │
          Binary Protocol
                  │
          Event Loop / Network
                  │
          Partition Router
                  │
     ┌────────────┴────────────┐
     │            │            │
 Partition 0  Partition 1  Partition 2
     │            │            │
 Hash Index   Hash Index   Hash Index
     │            │            │
 Off-Heap     Off-Heap     Off-Heap
    Pages         Pages        Pages

That document becomes the best entry point for new contributors, while the earlier RFCs remain focused on the details of each subsystem.

This is one of the most important concepts you'll need for Shiden. Once you understand lock-free MPSC queues, you'll understand how Netty hands work to your partitions without locks.

Let's build one from first principles instead of jumping into the algorithm.

The Problem

Suppose you have

             EventLoop 1
                   │
                   │
             EventLoop 2
                   │
                   │
             EventLoop 3
                   │
                   │
             EventLoop 4
                   │
                   ▼
             Partition Thread

All four EventLoops can receive requests for Partition 7.

They all need to place commands into the partition's queue.

Meanwhile

Partition Thread

is constantly removing commands.

So we have

4 Producers

↓

1 Consumer

This is exactly what an MPSC (Multi-Producer Single-Consumer) queue is designed for.

The Naive Solution

You might write

std::queue<Command> queue;
std::mutex mutex;

Producer

lock(mutex);

queue.push(cmd);

unlock(mutex);

Consumer

lock(mutex);

cmd = queue.front();

queue.pop();

unlock(mutex);

Works.

But imagine

EventLoop 1
EventLoop 2
EventLoop 3
EventLoop 4

all trying to lock the mutex simultaneously.

EventLoop1

        waiting
EventLoop2

        waiting
EventLoop3

        owns lock
EventLoop4

        waiting

Only one producer can enqueue.

Everyone else blocks.

This destroys scalability.

Lock-Free Idea

Instead of protecting the queue with a lock,

we make the queue itself safe for concurrent updates.

The most common implementation is a ring buffer.

Imagine an array.

+----+----+----+----+----+----+----+----+
|    |    |    |    |    |    |    |    |
+----+----+----+----+----+----+----+----+

Suppose it has 8 slots.

We keep two indexes.

Head

Tail

Like this

        head
         │
         ▼
+----+----+----+----+----+----+----+----+
| A  | B  | C  |    |    |    |    |    |
+----+----+----+----+----+----+----+----+
                   ▲
                   │
                  tail

Consumer reads from

head

Producers write at

tail
Why Is One Consumer Easy?

Only

Partition Thread

moves

head

Nobody else touches it.

Therefore

head

doesn't need synchronization.

Only one thread owns it.

Why Are Producers Hard?

Imagine

tail = 5

Two EventLoops arrive simultaneously.

Both read

tail = 5

Both decide

"I'll write into slot 5."

Boom.

One command overwrites the other.

The Atomic Solution

Instead of

tail++;

we use

atomic_fetch_add(&tail, 1);

Think of it as

Producer A

Tail = 5

↓

Gets slot 5

Tail becomes 6

At exactly the same moment

Producer B

Tail = 6

↓

Gets slot 6

Tail becomes 7

Every producer receives a unique slot.

No lock required.

Visual Example

Initially

Tail = 3

Producer 1

fetch_add(1)

↓

returns 3

↓

tail = 4

Producer 2

fetch_add(1)

↓

returns 4

↓

tail = 5

Producer 3

fetch_add(1)

↓

returns 5

↓

tail = 6

Nobody collides.

Writing the Data

Now Producer 1 owns

Slot 3

It writes

Command A

Producer 2 owns

Slot 4

It writes

Command B

Completely independently.

How Does the Consumer Know It's Ready?

This is the tricky part.

Imagine

Producer

↓

Reserve slot

↓

Writing...

What if

Consumer

looks at the slot

before

the producer finishes writing?

It might read

Half-written command

Bad.

Sequence Numbers

Many lock-free queues solve this by giving every slot a sequence number.

Initially

Slot

Sequence

0

1

2

3

4

When Producer reserves slot 3

it writes the command

then

updates the sequence number.

Write command

↓

Memory barrier

↓

Publish sequence

The consumer checks

Sequence == expected?

Only then does it read.

This guarantees it never sees partially written data.

Why Ring Buffers?

Because they're incredibly cache friendly.

Memory layout

+----------------------------------+

Command

Command

Command

Command

Command

+----------------------------------+

Everything is contiguous.

No pointers.

No heap allocations.

The CPU prefetcher loves this.

Compare with Linked List
Node

↓

Node

↓

Node

↓

Node

Each node

different memory page
different cache line
pointer chasing

Much slower.

Why JCTools Is Fast

Libraries like

JCTools

use several tricks:

Ring buffers
Atomic CAS operations
Cache-line padding
False-sharing prevention
Carefully chosen memory fences
No allocations during enqueue/dequeue

So enqueue is often just

Atomic increment

↓

Write array slot

↓

Publish sequence

Only a handful of CPU instructions.

What the Consumer Does

The Partition Thread continuously loops.

while (true)
{
    Command cmd = queue.poll();

    process(cmd);
}

Internally

Read sequence

↓

Ready?

↓

Yes

↓

Read command

↓

Advance head

No locks.

No waiting.

Why This Fits Shiden Perfectly

Recall your architecture.

          EventLoop 1
                 │
          EventLoop 2
                 │
          EventLoop 3
                 │
          EventLoop 4
                 ▼
      MPSC Input Queue
                 │
                 ▼
      Partition Owner Thread

Notice something important:

Only the EventLoops enqueue (multiple producers).
Only the Partition dequeues (single consumer).
The Partition is the sole owner of its state (hash index, pages, etc.).

This means the MPSC queue is the only synchronization point between the networking layer and the storage engine. Once a command leaves the queue, it is processed entirely by one thread with no further locks.

A subtle but important point

You do not want to implement your own MPSC queue for Shiden.

Writing a correct lock-free queue requires careful handling of:

memory ordering (acquire/release)
atomic operations
cache coherence
ABA problems (for pointer-based algorithms)
false sharing
platform-specific memory models

A tiny mistake may only appear once every few million operations, making it extremely difficult to debug.

For Shiden, using a mature library such as JCTools (or Agrona) is the right engineering choice. Your RFC should specify the concurrency model (MPSC) and the ownership rules, not the exact low-level algorithm. That lets you rely on a battle-tested implementation while keeping the architecture clean and maintainable.

u did not explain it avoid collision .. 

Exactly! I intentionally skipped the hardest part because it's where the magic happens. The question is:

If multiple producers write at the same time, how can they avoid writing to the same slot?

Let's walk through it like we're designing the queue ourselves.

The Problem

Suppose our ring buffer looks like this:

+----+----+----+----+----+----+----+----+
|    |    |    |    |    |    |    |    |
+----+----+----+----+----+----+----+----+

and

tail = 3

meaning the next free slot is

             tail
              │
              ▼
+----+----+----+----+----+----+----+----+
| A  | B  | C  |    |    |    |    |    |
+----+----+----+----+----+----+----+----+

Now imagine two EventLoops arrive at exactly the same nanosecond.

Producer 1

Producer 2

Both execute

slot = tail;
tail++;
Disaster

Timeline

Producer 1 reads tail = 3

Producer 2 reads tail = 3

Both think

"I own slot 3."

Producer 1 writes

Slot 3 = Command A

Producer 2 writes

Slot 3 = Command B

Result

Command A disappears.

This is a classic race condition.

Solution: Atomic Operations

Instead of

tail++;

we use

atomic_fetch_add(&tail, 1);

or internally

CAS (Compare-And-Swap)

These are CPU instructions.

The CPU guarantees

Only one core can successfully update the variable at a time.

Example

Suppose

tail = 3

Producer 1 executes

slot = atomic_fetch_add(tail,1);

CPU returns

slot = 3

and immediately changes

tail = 4

At exactly the same moment

Producer 2 executes

slot = atomic_fetch_add(tail,1);

Now the CPU sees

tail = 4

Producer 2 gets

slot = 4

Now

Producer 1 owns slot 3

Producer 2 owns slot 4

No collision.

Diagram

Before

Tail = 3

Producer1 ---> atomic_fetch_add()

Producer2 ---> atomic_fetch_add()

↓

CPU serializes them

↓

Producer1 gets slot 3

Tail = 4

↓

Producer2 gets slot 4

Tail = 5

Notice something important.

The producers themselves never synchronize.

The CPU does it for them.

But Another Problem Appears

Suppose Producer 1 gets

Slot 3

It starts writing

Command

Maybe the command is 200 bytes.

Writing takes time.

Meanwhile

the consumer is running.

It sees

tail = 4

It thinks

"Oh!

Slot 3 must be ready."

But Producer 1 is still copying.

The consumer reads

Half-written command.

Another race.

The Publish Step

Lock-free queues separate

Reserve slot

↓

Write data

↓

Publish slot

The producer does not immediately make the slot visible.

Instead

Reserve slot

↓

Copy Command

↓

Memory Fence

↓

Publish

Only after publishing can the consumer read it.

Sequence Numbers

This is how JCTools (and many lock-free queues) solve it.

Every slot has

struct Slot
{
    long sequence;

    Command command;
};

Initially

Slot 0 sequence = 0

Slot 1 sequence = 1

Slot 2 sequence = 2

Slot 3 sequence = 3

Producer wants slot 3.

First

sequence == 3 ?

Yes.

Slot is free.

Then

Write command

After finishing

sequence = 4

Now the consumer knows

Slot 3 is completely written.
Consumer

The consumer expects

sequence == head+1

Suppose

head = 3

It checks

sequence == 4 ?

If

No

the producer hasn't finished.

Don't read.

If

Yes

the producer already published it.

Safe to read.

Why Sequence Numbers?

Because

tail

only tells us

Someone reserved this slot.

It doesn't tell us

The producer finished writing.

Sequence numbers tell us exactly that.

Complete Timeline

Suppose

tail = 3

Producer 1

atomic_fetch_add()

↓

gets slot 3

↓

copy command

↓

store_release(sequence=4)

Producer 2

atomic_fetch_add()

↓

gets slot 4

↓

copy command

↓

store_release(sequence=5)

Consumer

head=3

↓

sequence==4 ?

↓

Yes

↓

Read command

↓

head=4

Then

sequence==5 ?

↓

Yes

↓

Read next command

Everything stays perfectly ordered.

Why No Locks?

Because the ownership is split.

Variable	Who modifies it?
tail	All producers (using atomic operations)
head	Consumer only
Slot contents	Exactly one producer
Sequence number	Producer publishes, consumer observes

Notice the beautiful property:

Two producers never write the same slot because atomic_fetch_add gives each one a unique index.
A producer never overwrites another producer's data because it owns its reserved slot exclusively.
The consumer never reads incomplete data because it waits for the producer to publish the slot via the sequence number.
The consumer is the only thread that advances head, so no synchronization is needed there.

This careful division of ownership is what makes a lock-free MPSC queue possible. It's not "magic"—it's a combination of atomic reservation, exclusive slot ownership, and safe publication using memory ordering and sequence numbers. That's why libraries like JCTools can achieve millions of operations per second without a mutex.

okey relays on CAS 

Yes, at the lowest level, lock-free MPSC queues fundamentally rely on atomic CPU instructions such as CAS (Compare-And-Swap), although the exact instruction depends on the algorithm.

There are actually two families of MPSC queue implementations.

1. CAS-based Queues (Linked List)

Many classic lock-free queues (like the Michael & Scott queue) use CAS heavily.

Enqueue looks conceptually like this:

Producer 1
    │
    ▼
 CAS(tail, oldTail, newTail)

Producer 2
    │
    ▼
 CAS(tail, oldTail, newTail)

If Producer 1 wins:

tail = NodeA

Producer 2's CAS fails:

Expected = oldTail

Actual = NodeA

↓

Retry

This is why CAS loops often look like

while (!CAS(...))
{
    // retry
}
2. Ring Buffers (JCTools, Agrona)

This is what Shiden would use.

These don't usually use CAS for every operation.

Instead they often use:

getAndIncrement()
getAndAdd()
compareAndSet() only when necessary
acquire/release memory fences

For example, JCTools' MpscArrayQueue uses an atomic producer index.

Conceptually:

long pIndex = producerIndex.getAndIncrement();

This instruction is implemented by the CPU as an atomic read-modify-write operation (often using instructions like LOCK XADD on x86), not necessarily a CAS loop.

Then

Producer 1

↓

gets index 5

Producer 2

↓

gets index 6

Producer 3

↓

gets index 7

No collisions.

Isn't getAndIncrement() also CAS?

This is an excellent question.

Conceptually: yes, it's solving the same synchronization problem.

Implementation-wise: not necessarily.

On modern CPUs there are several atomic instructions:

CAS (Compare-And-Swap)

Fetch-And-Add

Exchange (XCHG)

Fetch-And-Or

Fetch-And-And

For incrementing a counter,

the CPU can often do

LOCK XADD

instead of

CAS

retry

CAS

retry

CAS

XADD is usually more efficient because incrementing a counter is a very common operation.

Why not use a Mutex?

Suppose four EventLoops enqueue simultaneously.

Mutex:

Producer 1

owns lock

Producer 2

waiting

Producer 3

waiting

Producer 4

waiting

Atomic increment:

Producer 1

gets slot 10

Producer 2

gets slot 11

Producer 3

gets slot 12

Producer 4

gets slot 13

No sleeping.

No context switches.

No kernel involvement.

Where Does CAS Still Appear?

Even in ring-buffer queues, CAS is still used for certain situations:

resizing (if supported)
claiming a slot under contention
updating shared state
handling queue-full conditions

So CAS is still an important building block, but it's not necessarily the primitive used on every enqueue.

For Shiden

Since you're planning to use JCTools' MpscArrayQueue, you don't need to know every implementation detail, but it's useful to understand the high-level idea:

Multiple EventLoops
        │
        ▼
Atomic Producer Index
        │
        ▼
Each producer gets a unique slot
        │
        ▼
Producer writes command
        │
        ▼
Producer publishes slot
        │
        ▼
Single Partition Thread consumes

No two producers ever receive the same slot because the atomic producer index guarantees uniqueness, and the consumer never reads incomplete data because of the publish protocol and memory-ordering guarantees.

As you continue working on Shiden, I also recommend learning these CPU primitives in roughly this order:

Atomic variables (AtomicLong, AtomicReference)
CAS (Compare-And-Swap) and why it can fail
Memory ordering (Acquire/Release vs. Sequential Consistency)
Cache coherence (MESI protocol)
False sharing
Lock-free queue algorithms (JCTools, Agrona)

Understanding these six topics will make almost every lock-free data structure in high-performance systems much easier to reason about.