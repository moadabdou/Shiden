package shiden.poc.ring;

import java.util.Set;

/**
 * A Consistent Hash Ring.
 * 
 * TODO: Implement the ring routing logic.
 * You can start with a simple synchronized TreeMap-based ring, or challenge yourself 
 * to build a lock-free/allocation-free ring using volatile primitive arrays and binary search.
 */
public class ConsistentHashRing {

    public enum HashType {
        MURMUR3,
        JDK_HASHCODE
    }

    private final HashType hashType;
    private final int vnodesPerNode;

    public ConsistentHashRing(HashType hashType, int vnodesPerNode) {
        this.hashType = hashType;
        this.vnodesPerNode = vnodesPerNode;
    }

    /**
     * Adds a physical node to the ring, placing its virtual node replicas on the circle.
     */
    public void addNode(String node) {
        // TODO: Implement
    }

    /**
     * Removes a physical node and all its virtual node replicas from the ring.
     */
    public void removeNode(String node) {
        // TODO: Implement
    }

    /**
     * Routes a search key to the closest virtual node on the ring (clockwise).
     * This is the hot-path. Try to keep it thread-safe and allocation-free.
     */
    public String routeKey(String key) {
        // TODO: Implement
        return null;
    }

    public int getRingSize() {
        // TODO: Implement
        return 0;
    }

    public Set<String> getPhysicalNodes() {
        // TODO: Implement
        return null;
    }
}
