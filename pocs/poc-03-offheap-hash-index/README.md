# PoC-03: Off-Heap Robin Hood Hash Index & Incremental Rehashing

## 📌 Abstract
**PoC-03** validates the core data resolution mechanism of Shiden: the **Off-Heap Robin Hood Hash Index (RFC-002)**. 

Acting as the primary resolution engine mapping logical keys to physical `(Page ID, Slot ID)` storage pointers, this index must be cache-friendly, off-heap (0 GC impact), and capable of scaling to millions of entries without Stop-The-World (STW) latency spikes during map resizes.

---

## 🎯 Target SLOs & Validation Metrics

| Subsystem / Metric | Performance Target / Target SLO | Validation Method |
| :--- | :--- | :--- |
| **Lookup Latency (`GET`)** | **$< 15\text{ ns/op}$** | JMH Micro-benchmark |
| **Insertion Latency (`PUT`)** | **$< 25\text{ ns/op}$** | JMH Micro-benchmark |
| **Rehashing Tail Tax** | **$< 100\ \mu\text{s}$ p99.9 latency spike** | Simulator Latency Tracker |
| **Max Load Factor** | **85% Capacity** | Stress Benchmark |
| **GC Pause Impact** | **0 ms (100% Off-Heap)** | `GarbageCollectorMXBean` |
| **Fingerprint Rejection Rate** | **$> 99.9\%$ False Collision Filter** | Hash Index Simulator |

---

## 🏗️ Architectural Overview

```text
               Logical Key (String / byte[])
                            │
                            ▼
                    xxHash3 (64-bit Hash)
                            │
            ┌───────────────┴───────────────┐
            ▼                               ▼
     Bucket Index                      16-bit Fingerprint
    (Hash % Capacity)                 (Secondary Hash)
            │                               │
            └───────────────┬───────────────┘
                            ▼
              Off-Heap Robin Hood Bucket Array
     [ Distance (16b) | Fingerprint (16b) | Slot ID (16b) | Page ID (32b) ]
                            │
               Does Fingerprint Match?
              ┌─────────────┴─────────────┐
              ▼                           ▼
        (YES: Match)                 (NO: Skip candidate)
              │
              ▼
   Return Physical Pointer (Page ID, Slot ID)
```

---

## 📁 Directory Structure

```text
pocs/poc-03-offheap-hash-index/
├── README.md             # PoC-03 Documentation & SLO Targets
├── theory.md             # Technical & Architectural Design Theory
├── pom.xml               # Maven Build & JMH Dependencies (Pending)
├── src/                  # Source Code (Pending)
└── report/               # LaTeX PDF Report (Pending)
```

---

## 🚀 Execution & Verification (Planned)

Once source code is created, PoC-03 will execute two test suites:
1. **Index Simulator**: Evaluates load factor variance, probe length statistics, fingerprint rejection efficiency, and incremental rehashing cluster migration overhead.
2. **JMH Micro-Benchmark Suite**: Measures isolated nanosecond latencies for `GET`, `PUT`, `DELETE`, and incremental table expansion (`T0` $\rightarrow$ `T1`).
