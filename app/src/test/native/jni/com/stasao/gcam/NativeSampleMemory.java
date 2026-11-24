package com.stasao.gcam;

import java.nio.ByteBuffer;

/** Exercises the JNI ABI with a real JVM; the Android Kotlin wrapper uses it. */
public final class NativeSampleMemory {
    private static native long create(long maxBytes);
    private static native long acquire(long pool, int channel, int size);
    private static native ByteBuffer buffer(long pool, long lease);
    private static native boolean release(long pool, long lease);
    private static native void setBudget(long pool, long bytes);
    private static native void trim(long pool, long keepFreeBytes);
    private static native long[] snapshot(long pool);
    private static native boolean destroy(long pool);

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        System.load(args[0]);
        long pool = create(8L * 1024 * 1024);
        check(pool > 0, "create");
        long lease = acquire(pool, 0, 257);
        check(lease > 0, "acquire");
        ByteBuffer view = buffer(pool, lease);
        check(view != null && view.isDirect() && !view.isReadOnly(), "writable direct payload");
        check(view.capacity() == 257 && view.remaining() == 257, "exact byte capacity");
        for (int i = 0; i < 257; i++) view.put(i, (byte)i);
        ByteBuffer nextView = buffer(pool, lease);
        check(nextView != null && nextView.get(256) == 0 && nextView.get(255) == (byte)255,
            "shared lease bytes");
        check(!destroy(pool), "destroy refuses active JNI view");
        setBudget(pool, 0);
        trim(pool, 0);
        check(view.get(255) == (byte)255, "shrink preserves inflight bytes");
        check(acquire(pool, 1, 1) == 0, "shrink closes admission");
        long[] stats = snapshot(pool);
        check(stats.length == 5 && stats[0] == 0 && stats[1] == 4L * 1024 * 1024
            && stats[2] == 257 && stats[3] == 1, "JNI accounting");
        check(release(pool, lease), "release");
        check(!release(pool, lease) && buffer(pool, lease) == null, "stale lease");
        check(snapshot(pool)[1] == 0, "released shrunken allocation retired");
        check(destroy(pool) && !destroy(pool), "destroy and stale pool");
        check(buffer(pool, lease) == null, "stale pool buffer");
        System.out.println("PASS JNI writable direct view, shared bytes, exact size, shrink, stale IDs, destruction");
    }
}
