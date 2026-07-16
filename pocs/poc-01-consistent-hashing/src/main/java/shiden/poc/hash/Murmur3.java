package shiden.poc.hash;

public final class Murmur3 {
    private Murmur3() {}

    /**
     * Hashes a string using Murmur3 32-bit.
     */
    public static int hash32(String text) {
        if (text == null) {
            return 0;
        }
        return hash32(text, 0, text.length(), 0);
    }

    /**
     * Hashes a slice of a CharSequence using Murmur3 32-bit without temporary byte array allocations.
     */
    public static int hash32(CharSequence data, int offset, int len, int seed) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        int h1 = seed;
        int pos = offset;
        int end = offset + len;
        int k1 = 0;
        int k2 = 0;
        int shift = 0;
        int bits = 0;
        int nBytes = 0; // length in UTF8 bytes
        while (pos < end) {
            int code = data.charAt(pos++);
            if (code < 0x80) {
                k2 = code;
                bits = 8;
            } else if (code < 0x800) {
                k2 = (0xC0 | (code >> 6)) | ((0x80 | (code & 0x3F)) << 8);
                bits = 16;
            } else if (code < 0xD800 || code > 0xDFFF || pos >= end) {
                k2 = (0xE0 | (code >> 12)) | ((0x80 | ((code >> 6) & 0x3F)) << 8) | ((0x80 | (code & 0x3F)) << 16);
                bits = 24;
            } else {
                int utf32 = (int) data.charAt(pos++);
                utf32 = ((code - 0xD7C0) << 10) + (utf32 & 0x3FF);
                k2 = (0xff & (0xF0 | (utf32 >> 18))) | ((0x80 | ((utf32 >> 12) & 0x3F))) << 8 | ((0x80 | ((utf32 >> 6) & 0x3F))) << 16 | (0x80 | (utf32 & 0x3F)) << 24;
                bits = 32;
            }
            k1 |= k2 << shift;
            shift += bits;
            if (shift >= 32) {
                k1 *= c1;
                k1 = (k1 << 15) | (k1 >>> 17);
                k1 *= c2;
                h1 ^= k1;
                h1 = (h1 << 13) | (h1 >>> 19);
                h1 = h1 * 5 + 0xe6546b64;
                shift -= 32;
                if (shift != 0) {
                    k1 = k2 >>> (bits - shift);
                } else {
                    k1 = 0;
                }
                nBytes += 4;
            }
        }
        if (shift > 0) {
            nBytes += shift >> 3;
            k1 *= c1;
            k1 = (k1 << 15) | (k1 >>> 17);
            k1 *= c2;
            h1 ^= k1;
        }
        h1 ^= nBytes;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }

    /**
     * Hashes byte array data using Murmur3 32-bit.
     */
    public static int hash32(byte[] data, int length, int seed) {
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;
        int h1 = seed;
        int roundedEnd = (length & 0xfffffffc);

        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | (data[i + 3] << 24);
            k1 *= c1;
            k1 = (k1 << 15) | (k1 >>> 17);
            k1 *= c2;
            h1 ^= k1;
            h1 = (h1 << 13) | (h1 >>> 19);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        switch (length & 0x03) {
            case 3:
                k1 = (data[roundedEnd + 2] & 0xff) << 16;
            case 2:
                k1 |= (data[roundedEnd + 1] & 0xff) << 8;
            case 1:
                k1 |= (data[roundedEnd] & 0xff);
                k1 *= c1;
                k1 = (k1 << 15) | (k1 >>> 17);
                k1 *= c2;
                h1 ^= k1;
        }

        h1 ^= length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;

        return h1;
    }
}
