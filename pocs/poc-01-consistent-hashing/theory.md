# Theory Checkpoint: Consistent Hashing Ring

Consistent hashing is the foundational building block for partition routing in distributed data systems. It maps both cluster nodes and keys to a single circular space, ensuring minimal key movement when cluster membership changes.

---

## 🧠 Concepts Required

1. **Hash Ring Topology**: Mapping a $2^{32}-1$ integer space (or a 64-bit integer space) into a logical circle where the value after the maximum value wraps around to 0.
2. **Modulo Hashing ($hash(key) \pmod N$) Limitation**: Under simple modulo hashing, changing the node count $N$ (adding or removing a node) causes almost all keys to map to different nodes. The key movement rate is near 100%, causing massive network churn and database thrashing.
3. **Consistent Hashing Invariant**: When a node is added or removed, only $K/N$ keys need to be remapped on average, where $K$ is the total number of keys and $N$ is the number of nodes.
4. **Virtual Nodes (VNodes)**:
   - *Problem*: Simple consistent hashing maps each physical node to exactly one point on the ring. Due to hashing randomness, this leads to highly unbalanced segments (hotspots), where one node handles 10x more traffic than another.
   - *Solution*: Map each physical node to $V$ virtual positions (vnodes) scattered randomly across the ring. This averages out the segment sizes, leading to a uniform distribution of keys.
5. **Hash Functions (Collision & Distribution)**:
   - Cryptographic hashes (MD5, SHA-256): High collision resistance, but slow.
   - Non-cryptographic hashes (Murmur3, xxHash): High-speed, excellent distribution, much lower CPU overhead.

---

## 📚 Reference Material

* **Original Paper**: *Consistent Hashing and Random Trees: Distributed Caching Protocols for Relieving Hot Spots on the World Wide Web* (Karger et al., 1997). [PDF Link](https://www.cs.princeton.edu/courses/archive/fall09/cos518/papers/chash.pdf)
* **Real-World Case Study**: *Dynamo: Amazon’s Highly Available Key-value Store* (DeCandia et al., 2007) - Sections 4.2 (Partitioning Algorithm). [PDF Link](https://www.allthingsdistributed.com/files/amazon-dynamo-sosp2007.pdf)
* **Video Lecture**: *Consistent Hashing* - MIT 6.824: Distributed Systems.

---

## ❓ Core Questions

### 1. Why does this algorithm exist?
Traditional database partitioning relies on fixed hashing (e.g., modulo). When nodes fail or scale out, the mapping of keys to servers changes completely, invalidating caches and requiring huge data migrations. Consistent hashing exists to decouple partition routing from the exact number of active nodes.

### 2. What problem does it solve?
It minimizes the amount of data that must be migrated when a cluster scales up or down, while maintaining decentralized and predictable key lookup locations.

### 3. What assumptions does it make?
* It assumes keys are uniformly distributed by the hash function.
* It assumes nodes have similar performance characteristics, or that the virtual node count can be scaled proportionally to reflect node capacity (weighted consistent hashing).
* It assumes client nodes can either maintain a local copy of the ring or query a coordinator that does.

---

## 🎓 Graduation Criterion

To graduate from this PoC, you must be able to answer this question without referring to notes:
> **Can I explain why virtual nodes exist, and mathematically demonstrate how increasing the virtual node count balances the keys across physical servers?**
