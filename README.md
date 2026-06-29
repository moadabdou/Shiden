# ⚡ Shiden (紫電)
### *A Distributed, Off-Heap, Ultra-Low Latency In-Memory Data Grid (IMDG) Built from Scratch in Java 21*

[![Java Version](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk21)
[![Status](https://img.shields.io/badge/Status-Under%20Construction-yellow.svg)](#-warning-construction-zone)

![Shiden Cover](assets/cover.png)

> **Shiden** (named after the lightning-fast fighter aircraft *Shiden Kai*) is a pure-Java distributed in-memory data grid. It bypasses JVM runtime bottlenecks (like Garbage Collection pauses) by executing manual pointer-arithmetic over raw OS memory. It uses a hybrid fast/slow path concurrency model combining Netty Event Loops and lightweight Virtual Threads.

---

> [!WARNING]
> ### 🚧 Hard Hat Zone: Under Construction 🚧
> This project is currently in the active R&D phase. Parts of the codebase may contain raw bytes fighting for survival, half-completed consensus protocols, and Virtual Threads wandering around looking for work. Enter at your own risk, and don't touch any unaligned memory pointers without a safety harness!

---

## 🚀 Systems Engineering vs. Standard Java

| Goal | The Easy Way | The Shiden Way |
| :--- | :--- | :--- |
| **Memory Control** | Let the JVM allocate objects and rely on GC to clean up. | Bypass JVM heap completely. Manually align memory and compute byte offsets with the **FFM API (`MemorySegment`)**. |
| **Concurrency** | Use standard platform thread pools (like Tomcat). | **Hybrid Fast/Slow Path**: Use **Netty Event Loops** for fast non-blocking I/O and memory access, delegating slow/blocking tasks (AOF flushes) to **Virtual Threads**. |
| **Fault Tolerance** | Rely on managed cloud databases or replication frameworks. | **Phased Evolution**: Starts with an **AP-mode Eventual Consistency** engine (Gossip & Consistent Hashing) in v1.0, building towards a pure mathematical **Raft Consensus Protocol** (CP-mode) in future versions. |

---

## 🗺️ System Blueprint

To view the in-depth system architecture, protocol specifications, memory layouts, and roadmap, check out the detailed design document:

👉 **[Read the Technical Architecture Plan (plan/idea.md)](file:///home/moadabdou/coding/serious_projects/Shiden/plan/idea.md)**

---

## 💬 The GC's Reaction to Shiden

```
JVM GC: "Let me clean up those objects for you!"
Shiden: *Allocates everything off-heap using MemorySegment*
JVM GC: *Cries in 0B heap usage*
```
