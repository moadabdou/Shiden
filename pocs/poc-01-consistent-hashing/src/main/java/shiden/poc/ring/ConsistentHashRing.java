package shiden.poc.ring;

import shiden.poc.hash.Murmur3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Consistent Hash Ring.
 */
public class ConsistentHashRing {

    public enum HashType {
        MURMUR3,
        JDK_HASHCODE
    }

    private final HashType hashType;
    private final int vnodesPerNode;
    private final int numSlots;

    // Thread-safe set of physical nodes
    private final Set<String> physicalNodes = ConcurrentHashMap.newKeySet();

    // Volatile holder for current ring state to allow lock-free lookups
    private volatile RingState ringState = new RingState(new int[0], new String[0]);

    // Volatile slot table for Strategy B (Fixed-size partitions)
    private volatile String[] slotTable;

    private static class RingState {
        final int[] hashes;
        final String[] nodes;

        RingState(int[] hashes, String[] nodes) {
            this.hashes = hashes;
            this.nodes = nodes;
        }
    }

    private static class RingEntry {
        final int hash;
        final String node;

        RingEntry(int hash, String node) {
            this.hash = hash;
            this.node = node;
        }
    }

    public ConsistentHashRing(HashType hashType, int vnodesPerNode) {
        this(hashType, vnodesPerNode, 0);
    }

    public ConsistentHashRing(HashType hashType, int vnodesPerNode, int numSlots) {
        this.hashType = hashType;
        this.vnodesPerNode = vnodesPerNode;
        this.numSlots = numSlots;
        this.slotTable = numSlots > 0 ? new String[numSlots] : null;
    }

    /**
     * Adds a physical node to the ring, placing its virtual node replicas on the circle.
     */
    public synchronized void addNode(String node) {
        if (physicalNodes.add(node)) {
            rebuildRing();
        }
    }

    /**
     * Removes a physical node and all its virtual node replicas from the ring.
     */
    public synchronized void removeNode(String node) {
        if (physicalNodes.remove(node)) {
            rebuildRing();
        }
    }

    /**
     * Routes a search key to the closest virtual node on the ring (clockwise).
     * If slots are enabled, uses the fast slot table mapping instead.
     */
    public String routeKey(String key) {
        if (numSlots > 0) {
            int slotId = getSlotForKey(key);
            String[] currentTable = this.slotTable;
            if (currentTable == null || slotId < 0 || slotId >= currentTable.length) {
                return null;
            }
            return currentTable[slotId];
        }

        return routeKeyOnState(this.ringState, key);
    }

    private String routeKeyOnState(RingState state, String key) {
        if (state.hashes.length == 0) {
            return null;
        }
        int hash = calculateHash(key);
        int idx = Arrays.binarySearch(state.hashes, hash);
        if (idx < 0) {
            idx = -idx - 1;
            if (idx == state.hashes.length) {
                idx = 0;
            }
        }
        return state.nodes[idx];
    }

    public int getRingSize() {
        return ringState.hashes.length;
    }

    public Set<String> getPhysicalNodes() {
        return Collections.unmodifiableSet(physicalNodes);
    }

    public int getNumSlots() {
        return numSlots;
    }

    public String[] getSlotTable() {
        return slotTable == null ? null : slotTable.clone();
    }

    public int getSlotForKey(String key) {
        if (numSlots <= 0) {
            return -1;
        }
        return (calculateHash(key) & 0x7fffffff) % numSlots;
    }

    public String routeSlot(int slotId) {
        String[] currentTable = this.slotTable;
        if (currentTable == null || slotId < 0 || slotId >= numSlots) {
            return null;
        }
        return currentTable[slotId];
    }

    private int calculateHash(String value) {
        if (hashType == HashType.MURMUR3) {
            return Murmur3.hash32(value);
        } else {
            return value.hashCode();
        }
    }

    private void rebuildRing() {
        if (physicalNodes.isEmpty()) {
            ringState = new RingState(new int[0], new String[0]);
            if (numSlots > 0) {
                Arrays.fill(slotTable, null);
            }
            return;
        }

        int totalVNodes = physicalNodes.size() * vnodesPerNode;
        List<RingEntry> entries = new ArrayList<>(totalVNodes);

        for (String pNode : physicalNodes) {
            for (int i = 0; i < vnodesPerNode; i++) {
                String vnodeName = pNode + "#" + i;
                int hash = calculateHash(vnodeName);
                entries.add(new RingEntry(hash, pNode));
            }
        }

        // Sort by hash. Tie-break using the node name for deterministic ordering.
        entries.sort((a, b) -> {
            int cmp = Integer.compare(a.hash, b.hash);
            if (cmp != 0) {
                return cmp;
            }
            return a.node.compareTo(b.node);
        });

        int[] hashes = new int[entries.size()];
        String[] nodes = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            hashes[i] = entries.get(i).hash;
            nodes[i] = entries.get(i).node;
        }

        RingState newRingState = new RingState(hashes, nodes);
        this.ringState = newRingState;

        // Rebuild slot table if slots are enabled
        if (numSlots > 0) {
            String[] newSlotTable = new String[numSlots];
            for (int slotId = 0; slotId < numSlots; slotId++) {
                String slotKey = "slot-" + slotId;
                newSlotTable[slotId] = routeKeyOnState(newRingState, slotKey);
            }
            this.slotTable = newSlotTable;
        }
    }
}
