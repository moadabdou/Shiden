package shiden.poc.allocator;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;

import static java.lang.foreign.MemoryLayout.PathElement;

/**
 * Static FFM MemoryLayout and field offset definitions for Shiden's internal metadata structures.
 * <p>
 * Pre-computes field byte offsets using {@link MemoryLayout#byteOffset(PathElement...)} and uses
 * direct {@link MemorySegment#set(ValueLayout.OfLong, long, long)} operations. The JVM's JIT compiler (C2)
 * optimizes access directly into raw CPU pointer-dereferencing assembly instructions
 * (e.g. {@code mov [segment + offset], val}) with zero wrapper object overhead.
 * </p>
 */
public final class MemoryLayouts {

    private MemoryLayouts() {}

    // =========================================================================
    // 1. Off-Heap Hash Index Bucket (16 Bytes) - RFC-002
    // =========================================================================
    // Layout:
    // [ 0..7 ] hashKey    : long (8 bytes)
    // [ 8..11] pageId     : int  (4 bytes)
    // [12..13] slotId     : short(2 bytes)
    // [14..15] flags      : short(2 bytes)
    public static final StructLayout HASH_INDEX_BUCKET_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("hashKey"),
        ValueLayout.JAVA_INT.withName("pageId"),
        ValueLayout.JAVA_SHORT.withName("slotId"),
        ValueLayout.JAVA_SHORT.withName("flags")
    );

    public static final long BUCKET_HASH_OFFSET = HASH_INDEX_BUCKET_LAYOUT.byteOffset(PathElement.groupElement("hashKey"));
    public static final long BUCKET_PAGE_ID_OFFSET = HASH_INDEX_BUCKET_LAYOUT.byteOffset(PathElement.groupElement("pageId"));
    public static final long BUCKET_SLOT_ID_OFFSET = HASH_INDEX_BUCKET_LAYOUT.byteOffset(PathElement.groupElement("slotId"));
    public static final long BUCKET_FLAGS_OFFSET = HASH_INDEX_BUCKET_LAYOUT.byteOffset(PathElement.groupElement("flags"));

    // Helper methods for writing Hash Index Bucket fields
    public static void setBucketHash(MemorySegment segment, long slotBaseOffset, long hashKey) {
        segment.set(ValueLayout.JAVA_LONG, slotBaseOffset + BUCKET_HASH_OFFSET, hashKey);
    }

    public static void setBucketPageId(MemorySegment segment, long slotBaseOffset, int pageId) {
        segment.set(ValueLayout.JAVA_INT, slotBaseOffset + BUCKET_PAGE_ID_OFFSET, pageId);
    }

    public static void setBucketSlotId(MemorySegment segment, long slotBaseOffset, short slotId) {
        segment.set(ValueLayout.JAVA_SHORT, slotBaseOffset + BUCKET_SLOT_ID_OFFSET, slotId);
    }

    public static void setBucketFlags(MemorySegment segment, long slotBaseOffset, short flags) {
        segment.set(ValueLayout.JAVA_SHORT, slotBaseOffset + BUCKET_FLAGS_OFFSET, flags);
    }

    public static long getBucketHash(MemorySegment segment, long slotBaseOffset) {
        return segment.get(ValueLayout.JAVA_LONG, slotBaseOffset + BUCKET_HASH_OFFSET);
    }

    public static int getBucketPageId(MemorySegment segment, long slotBaseOffset) {
        return segment.get(ValueLayout.JAVA_INT, slotBaseOffset + BUCKET_PAGE_ID_OFFSET);
    }

    public static short getBucketSlotId(MemorySegment segment, long slotBaseOffset) {
        return segment.get(ValueLayout.JAVA_SHORT, slotBaseOffset + BUCKET_SLOT_ID_OFFSET);
    }

    // =========================================================================
    // 2. Inter-Partition Mailbox Queue Node (64 Bytes / 1 Cache Line) - RFC-003/004
    // =========================================================================
    // Layout:
    // [ 0..7 ] sequence       : long (8 bytes)
    // [ 8..11] partitionId    : int  (4 bytes)
    // [12..15] commandCode    : int  (4 bytes)
    // [16..23] payloadAddress : long (8 bytes)
    // [24..31] timestamp      : long (8 bytes)
    // [32..63] padding        : 32 bytes (pads struct to exactly 64-byte cache line)
    public static final StructLayout MAILBOX_NODE_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("sequence"),
        ValueLayout.JAVA_INT.withName("partitionId"),
        ValueLayout.JAVA_INT.withName("commandCode"),
        ValueLayout.JAVA_LONG.withName("payloadAddress"),
        ValueLayout.JAVA_LONG.withName("timestamp"),
        MemoryLayout.paddingLayout(32)
    );

    public static final long MAILBOX_SEQUENCE_OFFSET = MAILBOX_NODE_LAYOUT.byteOffset(PathElement.groupElement("sequence"));
    public static final long MAILBOX_PARTITION_ID_OFFSET = MAILBOX_NODE_LAYOUT.byteOffset(PathElement.groupElement("partitionId"));
    public static final long MAILBOX_COMMAND_CODE_OFFSET = MAILBOX_NODE_LAYOUT.byteOffset(PathElement.groupElement("commandCode"));
    public static final long MAILBOX_PAYLOAD_ADDR_OFFSET = MAILBOX_NODE_LAYOUT.byteOffset(PathElement.groupElement("payloadAddress"));
    public static final long MAILBOX_TIMESTAMP_OFFSET = MAILBOX_NODE_LAYOUT.byteOffset(PathElement.groupElement("timestamp"));
}
