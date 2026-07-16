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
    private volatile RingState ringState;

    private static class RingState {
        final int[] hashes;
        final String[] nodes;
        final String[] slotTable;

        RingState(int[] hashes, String[] nodes, String[] slotTable) {
            this.hashes = hashes;
            this.nodes = nodes;
            this.slotTable = slotTable;
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
        if (numSlots < 0 || (numSlots > 0 && (numSlots & (numSlots - 1)) != 0)) {
            throw new IllegalArgumentException("Number of slots must be a power of 2");
        }
        this.hashType = hashType;
        this.vnodesPerNode = vnodesPerNode;
        this.numSlots = numSlots;
        this.ringState = new RingState(new int[0], new String[0], numSlots > 0 ? new String[numSlots] : null);
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
        RingState state = this.ringState;
        if (numSlots > 0) {
            int slotId = getSlotForKey(key);
            String[] currentTable = state.slotTable;
            if (currentTable == null || slotId < 0 || slotId >= currentTable.length) {
                return null;
            }
            return currentTable[slotId];
        }

        return routeKeyOnState(state, key);
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
        String[] table = ringState.slotTable;
        return table == null ? null : table.clone();
    }

    public int getSlotForKey(String key) {
        if (numSlots <= 0) {
            return -1;
        }
        return calculateHash(key) & (numSlots - 1);
    }

    public String routeSlot(int slotId) {
        String[] currentTable = ringState.slotTable;
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
            ringState = new RingState(new int[0], new String[0], numSlots > 0 ? new String[numSlots] : null);
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

        // Create a temporary state to route the slots
        RingState tempState = new RingState(hashes, nodes, null);

        // Rebuild slot table if slots are enabled
        String[] newSlotTable = null;
        if (numSlots > 0) {
            newSlotTable = new String[numSlots];
            for (int slotId = 0; slotId < numSlots; slotId++) {
                String slotKey = "slot-" + slotId;
                newSlotTable[slotId] = routeKeyOnState(tempState, slotKey);
            }
        }

        this.ringState = new RingState(hashes, nodes, newSlotTable);
    }
}
