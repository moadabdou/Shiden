package shiden.poc.hashindex;

/**
 * Fast zero-allocation 64-bit hash function & 16-bit fingerprint generator.
 * <p>
 * Uses MurmurHash3 / Avalanche mixing algorithm to produce high-entropy
 * bits across all bit positions, ensuring uniform hash table index distribution
 * and independent secondary 16-bit key fingerprints.
 * </p>
 */
public final class XxHash3 {

    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;

    private XxHash3() {}

    /**
     * Hashes a 64-bit primitive key into a high-entropy 64-bit hash.
     */
    public static long hash64(long key) {
        long k = key;
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return k;
    }

    /**
     * Hashes a String key into a high-entropy 64-bit hash without heap allocations.
     */
    public static long hash64(String key) {
        long h = 0x97f4b979b9786183L;
        int len = key.length();
        for (int i = 0; i < len; i++) {
            long c = key.charAt(i);
            c *= C1;
            c = Long.rotateLeft(c, 31);
            c *= C2;
            h ^= c;
            h = Long.rotateLeft(h, 27);
            h = h * 5 + 0x52dce729;
        }
        h ^= len;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    /**
     * Extracts a 16-bit secondary fingerprint from the top 16 bits of a 64-bit hash.
     */
    public static short extractFingerprint(long hash) {
        return (short) ((hash >>> 48) & 0xFFFF);
    }
}
