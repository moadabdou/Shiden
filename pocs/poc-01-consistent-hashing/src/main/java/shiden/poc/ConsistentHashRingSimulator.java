package shiden.poc;

import shiden.poc.hash.Murmur3;
import shiden.poc.ring.ConsistentHashRing;
import shiden.poc.ring.ConsistentHashRing.HashType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConsistentHashRingSimulator {

    private static final int TOTAL_KEYS = 100_000;
    private static final List<String> INITIAL_NODES = List.of("node-1", "node-2", "node-3", "node-4", "node-5");

    public static void main(String[] args) {
        System.out.println("=========================================================================");
        System.out.println("             SHIDEN CONSISTENT HASHING RING SIMULATOR                     ");
        System.out.println("=========================================================================");

        // Test 1: Load Distribution Variance (Murmur3 vs. JDK hashCode)
        runLoadDistributionTest();

        System.out.println();

        // Test 2: Scale-Up Key Migration (Consistent Hashing vs. Modulo Hashing)
        runScaleUpTest();

        System.out.println();

        // Test 3: Migration Routing Overhead Simulation (Strategy A vs. Strategy B)
        runMigrationOverheadSimulation();
    }

    private static void runLoadDistributionTest() {
        System.out.println("--- Test 1: Load Distribution Variance (100,000 Keys, 5 Nodes) ---");
        System.out.printf("%-30s | %-12s | %-25s | %s\n", "Hash Type / Mode", "VNodes/Node", "Coeff. of Variation (%)", "Raw Node Distribution");
        System.out.println("-----------------------------------------------------------------------------------------------------------------------------");

        int[] vnodeScales = {1, 10, 50, 100, 250, 500};

        // Test MURMUR3 (Consistent Hashing)
        for (int v : vnodeScales) {
            runSingleDistributionTest(HashType.MURMUR3, v, 0);
        }

        // Test JDK_HASHCODE (Consistent Hashing)
        for (int v : vnodeScales) {
            runSingleDistributionTest(HashType.JDK_HASHCODE, v, 0);
        }

        System.out.println("---------------------------------------- SLOT HASHING (Strategy B) ---------------------------------------------------------");
        // Test Slot-based hashing (Q=1024 and Q=16384, with V=150)
        runSingleDistributionTest(HashType.MURMUR3, 150, 1024);
        runSingleDistributionTest(HashType.MURMUR3, 150, 16384);
        runSingleDistributionTest(HashType.JDK_HASHCODE, 150, 1024);
    }

    private static void runSingleDistributionTest(HashType hashType, int vnodes, int slots) {
        ConsistentHashRing ring = new ConsistentHashRing(hashType, vnodes, slots);
        for (String node : INITIAL_NODES) {
            ring.addNode(node);
        }

        Map<String, Integer> distribution = new HashMap<>();
        for (String node : INITIAL_NODES) {
            distribution.put(node, 0);
        }

        for (int i = 0; i < TOTAL_KEYS; i++) {
            String key = "key-" + i;
            String node = ring.routeKey(key);
            distribution.put(node, distribution.getOrDefault(node, 0) + 1);
        }

        // Calculate Coeff of Variation (CV)
        double mean = TOTAL_KEYS / (double) INITIAL_NODES.size();
        double varianceSum = 0;
        for (String node : INITIAL_NODES) {
            int count = distribution.get(node);
            varianceSum += Math.pow(count - mean, 2);
        }
        double stdDev = Math.sqrt(varianceSum / INITIAL_NODES.size());
        double cv = (stdDev / mean) * 100.0;

        // Print representation of raw distribution
        StringBuilder distStr = new StringBuilder("{");
        for (int i = 0; i < INITIAL_NODES.size(); i++) {
            String node = INITIAL_NODES.get(i);
            distStr.append(node).append("=").append(distribution.get(node));
            if (i < INITIAL_NODES.size() - 1) {
                distStr.append(", ");
            }
        }
        distStr.append("}");

        String label = hashType.name();
        if (slots > 0) {
            label += " (SLOTS Q=" + slots + ")";
        }

        System.out.printf("%-30s | %-12d | %-25.2f | %s\n", label, vnodes, cv, distStr.toString());
    }

    private static void runScaleUpTest() {
        System.out.println("--- Test 2: Scale-Up Key Migration (5 Nodes to 6 Nodes) ---");

        // 1. Consistent Hashing (V=150)
        ConsistentHashRing ring = new ConsistentHashRing(HashType.MURMUR3, 150);
        for (String node : INITIAL_NODES) {
            ring.addNode(node);
        }

        String[] originalConsistent = new String[TOTAL_KEYS];
        for (int i = 0; i < TOTAL_KEYS; i++) {
            originalConsistent[i] = ring.routeKey("key-" + i);
        }

        // Add 6th node
        ring.addNode("node-6");

        String[] newConsistent = new String[TOTAL_KEYS];
        int consistentMigrated = 0;
        for (int i = 0; i < TOTAL_KEYS; i++) {
            newConsistent[i] = ring.routeKey("key-" + i);
            if (!originalConsistent[i].equals(newConsistent[i])) {
                consistentMigrated++;
            }
        }

        double consistentPct = (consistentMigrated / (double) TOTAL_KEYS) * 100.0;

        // 2. Modulo Hashing (using Murmur3 for routing)
        List<String> fiveNodes = INITIAL_NODES;
        List<String> sixNodes = new ArrayList<>(INITIAL_NODES);
        sixNodes.add("node-6");

        int moduloMigrated = 0;
        for (int i = 0; i < TOTAL_KEYS; i++) {
            String key = "key-" + i;
            int hash = Murmur3.hash32(key);
            int idx5 = (hash & 0x7fffffff) % 5;
            int idx6 = (hash & 0x7fffffff) % 6;

            String node5 = fiveNodes.get(idx5);
            String node6 = sixNodes.get(idx6);

            if (!node5.equals(node6)) {
                moduloMigrated++;
            }
        }

        double moduloPct = (moduloMigrated / (double) TOTAL_KEYS) * 100.0;

        // 3. Slot Hashing (Q=1024, V=150)
        ConsistentHashRing slotRing = new ConsistentHashRing(HashType.MURMUR3, 150, 1024);
        for (String node : INITIAL_NODES) {
            slotRing.addNode(node);
        }

        String[] originalSlots = new String[TOTAL_KEYS];
        for (int i = 0; i < TOTAL_KEYS; i++) {
            originalSlots[i] = slotRing.routeKey("key-" + i);
        }

        // Add 6th node
        slotRing.addNode("node-6");

        int slotsMigrated = 0;
        for (int i = 0; i < TOTAL_KEYS; i++) {
            if (!originalSlots[i].equals(slotRing.routeKey("key-" + i))) {
                slotsMigrated++;
            }
        }

        double slotsPct = (slotsMigrated / (double) TOTAL_KEYS) * 100.0;

        // Model migration durations for a 10 GB dataset
        // Modulo: moves 83.49% of 10GB = 8.55GB. At 100 MB/s (typical db inserts), takes ~85.5 seconds
        // Consistent Hashing: moves 14.56% of 10GB = 1.49GB. At 15 MB/s (slowed by 900 small token range files / random seeks), takes ~99.4 seconds
        // Slot Hashing: moves 13.34% of 10GB = 1.37GB. At 120 MB/s (fast contiguous slot segment streaming), takes ~11.4 seconds
        double moduloBytes = (moduloPct / 100.0) * 10.0 * 1024; // MB
        double consistentBytes = (consistentPct / 100.0) * 10.0 * 1024; // MB
        double slotsBytes = (slotsPct / 100.0) * 10.0 * 1024; // MB

        double moduloTime = moduloBytes / 100.0; // seconds
        double consistentTime = consistentBytes / 15.0; // seconds
        double slotsTime = slotsBytes / 120.0; // seconds

        System.out.printf("Naive Modulo Hashing Migrated          : %d / %d keys (%.2f%%) | Est. Time (10GB): %.1fs\n", moduloMigrated, TOTAL_KEYS, moduloPct, moduloTime);
        System.out.printf("Consistent Hashing (V=150) Migrated    : %d / %d keys (%.2f%%) | Est. Time (10GB): %.1fs (Slowed by Random VNode I/O)\n", consistentMigrated, TOTAL_KEYS, consistentPct, consistentTime);
        System.out.printf("Slot-based Hashing (Q=1024, V=150)     : %d / %d keys (%.2f%%) | Est. Time (10GB): %.1fs (Fast Sequential Slot Stream)\n", slotsMigrated, TOTAL_KEYS, slotsPct, slotsTime);
        System.out.println("Theoretical expected migration under Consistent Hashing: ~16.67% (1/6th of keys)");
        System.out.println("Theoretical expected migration under Modulo Hashing    : ~83.33% (5/6th of keys)");
    }

    private static void runMigrationOverheadSimulation() {
        System.out.println("--- Test 3: Migration Routing Overhead Simulation (Strategy A vs. Strategy B) ---");

        // Warm up JVM JIT compiler for both routing methods
        ConsistentHashRing warmupRing = new ConsistentHashRing(HashType.MURMUR3, 150);
        for (String node : INITIAL_NODES) warmupRing.addNode(node);
        warmupRing.addNode("node-6");
        for (int i = 0; i < 5000; i++) warmupRing.routeKey("key-" + i);

        ConsistentHashRing warmupSlotRing = new ConsistentHashRing(HashType.MURMUR3, 150, 1024);
        for (String node : INITIAL_NODES) warmupSlotRing.addNode(node);
        warmupSlotRing.addNode("node-6");
        for (int i = 0; i < 5000; i++) warmupSlotRing.routeKey("key-" + i);

        // --- Strategy A (VNodes / Consistent Hashing) ---
        ConsistentHashRing ring = new ConsistentHashRing(HashType.MURMUR3, 150);
        for (String node : INITIAL_NODES) {
            ring.addNode(node);
        }

        // We track the original mapping for each key
        String[] originalConsistent = new String[TOTAL_KEYS];
        for (int i = 0; i < TOTAL_KEYS; i++) {
            originalConsistent[i] = ring.routeKey("key-" + i);
        }

        long startA = System.nanoTime();
        // Scale up: add node-6
        ring.addNode("node-6");

        // System must scan all 100,000 keys to check if they have routed to a different node
        List<String> keysToMigrateA = new ArrayList<>();
        for (int i = 0; i < TOTAL_KEYS; i++) {
            String key = "key-" + i;
            String newRoute = ring.routeKey(key);
            if (!originalConsistent[i].equals(newRoute)) {
                keysToMigrateA.add(key);
            }
        }
        long durationA = System.nanoTime() - startA;

        // --- Strategy B (Slots / Fixed-size partitions) ---
        ConsistentHashRing slotRing = new ConsistentHashRing(HashType.MURMUR3, 150, 1024);
        for (String node : INITIAL_NODES) {
            slotRing.addNode(node);
        }

        long startB = System.nanoTime();
        // Record slot table state before scale-up
        String[] oldSlots = slotRing.getSlotTable();

        // Scale up: add node-6
        slotRing.addNode("node-6");

        // Record slot table state after scale-up
        String[] newSlots = slotRing.getSlotTable();

        // We only scan the 1024 slot mappings, not the keys!
        List<Integer> slotsToMigrate = new ArrayList<>();
        for (int i = 0; i < oldSlots.length; i++) {
            if (!oldSlots[i].equals(newSlots[i])) {
                slotsToMigrate.add(i);
            }
        }
        long durationB = System.nanoTime() - startB;

        System.out.printf("Strategy A (Scan-based Consistent Hashing) - Scanned %d keys:\n", TOTAL_KEYS);
        System.out.printf("  Time to identify migration: %,d ns (%.3f ms)\n", durationA, durationA / 1_000_000.0);
        System.out.printf("  Migrating key count       : %d\n", keysToMigrateA.size());
        
        System.out.printf("Strategy B (Slot-based Hashing) - Inspected %d slots (0 keys scanned):\n", oldSlots.length);
        System.out.printf("  Time to identify migration: %,d ns (%.3f ms)\n", durationB, durationB / 1_000_000.0);
        System.out.printf("  Migrating slot count      : %d slots\n", slotsToMigrate.size());
        
        System.out.printf("Speedup: %.1fx faster CPU identification\n", (double) durationA / durationB);
        System.out.println("\nNote: Under Strategy B, data is transferred as entire partition/slot files (e.g., SSTables),");
        System.out.println("completely bypassing individual key traversal and disk seek overhead during cluster re-balancing.");
    }
}
