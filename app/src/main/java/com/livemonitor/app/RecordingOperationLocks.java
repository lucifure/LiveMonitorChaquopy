package com.livemonitor.app;

import java.util.function.Supplier;

/** Bounded keyed locks for short recording lifecycle and per-video decisions. */
final class RecordingOperationLocks {
    private static final int LOCK_COUNT = 64;

    private static final Object[] RECORDING_LOCKS = createLocks();
    private static final Object[] VIDEO_LOCKS = createLocks();

    Object forRecording(String recordingId) {
        return RECORDING_LOCKS[indexFor(recordingId)];
    }

    Object forVideo(String videoId) {
        return VIDEO_LOCKS[indexFor(videoId)];
    }

    <T> T withRecordingLock(String recordingId, Supplier<T> operation) {
        synchronized (forRecording(recordingId)) {
            return operation.get();
        }
    }

    <T> T withVideoLock(String videoId, Supplier<T> operation) {
        synchronized (forVideo(videoId)) {
            return operation.get();
        }
    }

    private static Object[] createLocks() {
        Object[] locks = new Object[LOCK_COUNT];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private static int indexFor(String key) {
        int hash = key == null ? 0 : key.hashCode();
        hash ^= hash >>> 16;
        return hash & (LOCK_COUNT - 1);
    }
}
