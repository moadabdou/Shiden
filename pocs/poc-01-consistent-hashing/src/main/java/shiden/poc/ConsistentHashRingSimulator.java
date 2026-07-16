package shiden.poc;

public class ConsistentHashRingSimulator {

    private static final int TOTAL_KEYS = 100_000;

    public static void main(String[] args) {
        System.out.println("=========================================================================");
        System.out.println("             SHIDEN CONSISTENT HASHING RING SIMULATOR                     ");
        System.out.println("=========================================================================");

        // TODO Test 1: Load Distribution Variance (Murmur3 vs. JDK hashCode)
        // 1. Populate a ring with 5 physical nodes.
        // 2. Scale virtual nodes (V) from 1, 10, 50, 100, 250, to 500.
        // 3. Route 100,000 keys and record how many keys land on each node.
        // 4. Calculate the standard deviation and coefficient of variation (%) for each setup.
        // 5. Compare Murmur3 vs JDK hashCode and observe the clustering.

        // TODO Test 2: Scale-Up Key Migration (Consistent Hashing vs. Modulo Hashing)
        // 1. Map 100,000 keys to a 5-node cluster. Record their mapping.
        // 2. Add a 6th node.
        // 3. Re-route the 100,000 keys.
        // 4. Count how many keys changed nodes under Naive Modulo vs. Consistent Hashing.
    }
}
