# Systems Research Digest: Consistent Hashing

This document distills the essential systems concepts from the foundational consistent hashing papers:
1. **Karger et al. (1997)**: *Consistent Hashing and Random Trees* (The academic foundation).
2. **DeCandia et al. (Amazon Dynamo, 2007)**: *Dynamo: Amazon’s Highly Available Key-value Store* (The production implementation).

---

## 📄 Paper 1: Consistent Hashing and Random Trees (Karger et al., 1997)

### 1. The Modulo Hashing Problem
In a standard caching or database system, keys are assigned to $N$ servers using modulo arithmetic:
$$\text{server} = \text{hash}(\text{key}) \pmod N$$

When the cluster scales ($N \to N+1$ or $N \to N-1$), the modulo divisor changes. As a result, **almost every key hashes to a different server**. In databases, this triggers massive network-wide data replication. In caches, it causes a near 100% cache-miss storm, bringing down backend services.

### 2. Consistent Hashing Definition
Consistent hashing maps both **keys** and **nodes (buckets)** to a shared circular space, typically represented as the range $[0, 2^{32}-1]$.

```
          [h(node-1) = 500,000]
                 /     \
               /         \
   [h(key-1)] *           * [h(node-2) = 2,500,000]
             |             |
              \           /
                \       /
          [h(node-3) = 4,000,000]
```

* **Routing Rule**: To locate the server for a given `key`, compute $h(\text{key})$. Traverse the circle clockwise (increasing hash values) until you encounter the first server hash $h(\text{node}) \ge h(\text{key})$. 
* **Wrap-around**: If $h(\text{key})$ is greater than the largest server hash on the ring, wrap around and assign the key to the server with the smallest hash value.

### 3. Core Mathematical Properties
* **Balance**: Keys are distributed among servers as evenly as possible.
* **Monotonicity**: When a new node is added, keys only migrate *from* old nodes *to* the new node. Keys never shuffle between existing nodes.
* **Minimal Disruption ($K/N$)**: When adding or removing a node, only an average of $1/N$ fraction of the keys are relocated.

---

## 📄 Paper 2: Amazon Dynamo (SOSP 2007 - Section 4.2)

Amazon’s Dynamo paper took Karger's theoretical model and modified it to handle the operational realities of running a massive database at scale.

### 1. The Virtual Node (Token) Concept
In the basic Karger model, each physical server is mapped to exactly one point on the ring. This leads to two major operational issues:
1. **Non-Uniform Load (Hotspots)**: Due to the randomness of hash functions, the segments between server positions on the ring vary wildly. One server might end up owning 70% of the circle, while another owns only 5%.
2. **Skewed Failover**: If a server fails, its entire key range falls onto its immediate clockwise neighbor. This neighbor now bears double the load, often causing a cascading failure.

**The Solution: Virtual Nodes (vnodes / tokens)**
Instead of placing a physical machine once on the ring, Dynamo assigns each physical machine $V$ random positions on the circle.
* **Uniform Distribution**: Scattering hundreds of virtual nodes per physical machine breaks the ring into small, interleaved segments, ensuring that key distribution is balanced.
* **Symmetric Failover**: If a physical machine fails, its virtual nodes are deleted. The keys from those virtual nodes are inherited by various clockwise neighbors across the entire circle, distributing the failover load evenly.
* **Heterogeneous Capacity**: A powerful server can be assigned more virtual nodes (e.g., $V=500$), while an older, weaker server is assigned fewer (e.g., $V=100$), balancing load proportionally to hardware.

---

## 🛠️ The Partitioning Evolution: Tokens vs. Fixed Ranges

Section 4.2 and later retrospective discussions of Dynamo describe a critical design evolution that is highly relevant to **Shiden**.

```
Strategy A: Random Token Ranges (VNodes)
[--Node A--][---Node B---][--Node C--][--Node A--] (Boundaries shift when nodes join)

Strategy B: Fixed-Size Partitions (e.g., 1024 slots)
[Slot 1][Slot 2][Slot 3][Slot 4]...[Slot 1024]     (Boundaries NEVER shift)
Assign Slot 1 -> Node A, Slot 2 -> Node B, etc.
```

### Strategy A: Random Tokens (Original Dynamo)
* **How it works**: Virtual nodes are assigned random hash positions. A node's range is defined by the distance to its predecessor.
* **Drawback**: When a node joins or leaves, the partition boundaries change. To transfer data, nodes must scan their storage engines to extract keys that fall into the new ranges. This requires high disk I/O and CPU, and invalidates synchronization structures like Merkle Trees.

### Strategy B: Fixed-Size Partitions (Modern Systems / Redis Cluster)
* **How it works**: The hash space is divided into $Q$ equal-sized, fixed partitions (e.g., $Q = 1024$ or $Q = 16384$).
* **Mapping**: The consistent hashing ring maps these *partitions* (or "slots") to physical nodes. 
* **Key Routing**:
  1. $\text{partitionId} = \text{hash}(\text{key}) \pmod Q$
  2. $\text{node} = \text{partitionTable}[\text{partitionId}]$
* **Advantage**: Partition boundaries **never change**. When a node scales, entire partitions are moved as complete files (e.g., transferring pre-computed SSTables or log files). Merkle trees can be built per partition, meaning they do not need to be recalculated when partitions move.

---

## 💡 Practical Implications for Your Code

As you start writing `ConsistentHashRing.java`:
1. **Unsigned Wrap-around**: Since Java's `int` is signed, you must ensure that your binary search correctly wraps around. Standard `int` comparisons treat negative values as smaller than positive ones. Using a sorted array handles this, provided your index wrap-around condition is strictly based on the end of the array.
2. **Key representation**: Notice that the ring's routing logic does not care about what the key *is*—it only cares about the key's hash. A highly optimized routing path should operate on pre-computed hashes (`int`) to avoid string allocation.
