package com.livemonitor.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class RecordingRecoveryPolicyTest {
    @Test
    public void unknownStatusCanBePreservedWithoutChangingRecordingIdentityOrSegments() throws Exception {
        RecordingItem recording = new RecordingItem(
            "channel-id", "Channel", "https://example.test/channel", "video-id",
            "https://example.test/video", "Title", "480p", "/tmp/original.ts", "/tmp/final.mp4"
        );
        recording.addTempChunkPath("/tmp/original.part2.ts");
        recording.markRecording();
        String id = recording.getId();

        assertTrue(RecordingRecoveryPolicy.mayPreserveUnknownStatus(
            recording.getStatus(), false, false, false
        ));

        recording.markRecoverable("Stream status unknown.");
        RecordingItem restored = RecordingItem.fromJson(recording.toJson());

        assertEquals(id, restored.getId());
        assertEquals(RecordingItem.STATUS_RECOVERABLE, restored.getStatus());
        assertEquals("/tmp/original.ts", restored.getTempTsPath());
        assertEquals(recording.getTempSegmentPaths(), restored.getTempSegmentPaths());
    }

    @Test
    public void unknownStreamStatusNeverSelectsConfirmedEndFinalization() {
        assertFalse(RecordingRecoveryPolicy.shouldFinalizeAfterStreamStatus("UNKNOWN"));
        assertFalse(RecordingRecoveryPolicy.shouldFinalizeAfterStreamStatus("STILL_LIVE"));
        assertTrue(RecordingRecoveryPolicy.shouldFinalizeAfterStreamStatus("CONFIRMED_ENDED"));
    }

    @Test
    public void sameLiveVideoRoutesToExistingRecoveryInsteadOfCreatingNewItem() {
        assertTrue(RecordingRecoveryPolicy.shouldDeferNewRecording(true));
        assertTrue(RecordingRecoveryPolicy.shouldResumeRecoverable(
            RecordingItem.STATUS_RECOVERABLE, true, true
        ));
        assertFalse(RecordingRecoveryPolicy.shouldResumeRecoverable(
            RecordingItem.STATUS_RECOVERABLE, false, true
        ));
    }

    @Test
    public void scannedChunkPathMatchesExistingRecordingWithoutCreatingAnotherItem() {
        RecordingItem existing = new RecordingItem(
            "channel-id", "Channel", "https://example.test/channel", "video-id",
            "https://example.test/video", "Title", "480p", "/tmp/original.ts", "/tmp/final.mp4"
        );
        existing.addTempChunkPath("/tmp/original.part2.ts");

        assertTrue(RecordingRecoveryPolicy.containsTempSegmentPath(existing, "/tmp/original.ts"));
        assertTrue(RecordingRecoveryPolicy.containsTempSegmentPath(existing, "/tmp/original.part2.ts"));
        assertFalse(RecordingRecoveryPolicy.containsTempSegmentPath(existing, "/tmp/unrelated.ts"));
        assertFalse(RecordingRecoveryPolicy.containsTempSegmentPath(existing, ""));
    }

    @Test
    public void restoredWaitingChannelRetainsVideoIdentityForRecoveryLookup() {
        ChannelItem channel = new ChannelItem("https://example.test/channel");
        channel.markRecording("video-id", "https://example.test/video");

        channel.markWaitingForLive();

        assertFalse(channel.isRecording());
        assertTrue(channel.isSameCurrentVideo("video-id"));
        assertTrue(RecordingRecoveryPolicy.shouldResumeRecoverable(
            RecordingItem.STATUS_RECOVERABLE, channel.isSameCurrentVideo("video-id"), true
        ));
    }

    @Test
    public void delayedCallbackCannotFinalizeOrResetAlreadyRecoverableItem() {
        assertTrue(RecordingRecoveryPolicy.shouldIgnoreCompletionCallback(
            RecordingItem.STATUS_RECOVERABLE
        ));
        assertFalse(RecordingRecoveryPolicy.shouldIgnoreCompletionCallback(
            RecordingItem.STATUS_RECORDING
        ));
    }

    @Test
    public void staleStillLiveCheckCannotRestartAfterUnknownPreservation() throws Exception {
        RecordingOperationLocks lifecycleLocks = new RecordingOperationLocks();
        AtomicReference<String> persistedStatus = new AtomicReference<>(RecordingItem.STATUS_RECORDING);
        AtomicBoolean channelMonitoring = new AtomicBoolean(true);
        AtomicBoolean channelRecording = new AtomicBoolean(true);
        AtomicBoolean finalizing = new AtomicBoolean(false);
        AtomicInteger recorderDispatches = new AtomicInteger();
        CountDownLatch cleanExitCheckCompleted = new CountDownLatch(1);
        CountDownLatch allowRestartDecision = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> staleRestart = executor.submit(() -> {
                String callbackSnapshot = persistedStatus.get();
                assertEquals(RecordingItem.STATUS_RECORDING, callbackSnapshot);
                cleanExitCheckCompleted.countDown();
                await(allowRestartDecision);

                lifecycleLocks.withRecordingLock("recording-id", () -> {
                    String latestStatus = persistedStatus.get();
                    if (RecordingRecoveryPolicy.mayRestartAfterCleanExit(
                        latestStatus,
                        channelMonitoring.get(),
                        false,
                        finalizing.get(),
                        false
                    )) {
                        persistedStatus.set(RecordingItem.STATUS_RECORDING);
                        channelRecording.set(true);
                        recorderDispatches.incrementAndGet();
                    }
                    return null;
                });
            });

            assertTrue(cleanExitCheckCompleted.await(2, TimeUnit.SECONDS));
            lifecycleLocks.withRecordingLock("recording-id", () -> {
                persistedStatus.set(RecordingItem.STATUS_RECOVERABLE);
                channelMonitoring.set(true);
                channelRecording.set(false);
                return null;
            });
            allowRestartDecision.countDown();
            staleRestart.get(2, TimeUnit.SECONDS);

            assertEquals(RecordingItem.STATUS_RECOVERABLE, persistedStatus.get());
            assertFalse(channelRecording.get());
            assertEquals(0, recorderDispatches.get());
        } finally {
            allowRestartDecision.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void userPauseStopAndFinalizationPreventUnknownPreservation() {
        assertFalse(RecordingRecoveryPolicy.mayPreserveUnknownStatus(
            RecordingItem.STATUS_RECORDING, true, false, false
        ));
        assertFalse(RecordingRecoveryPolicy.mayPreserveUnknownStatus(
            RecordingItem.STATUS_PAUSED_BY_USER, false, false, false
        ));
        assertFalse(RecordingRecoveryPolicy.mayPreserveUnknownStatus(
            RecordingItem.STATUS_STOPPED_BY_USER, false, false, false
        ));
        assertFalse(RecordingRecoveryPolicy.mayPreserveUnknownStatus(
            RecordingItem.STATUS_RECORDING, false, true, false
        ));
        assertFalse(RecordingRecoveryPolicy.mayPreserveUnknownStatus(
            RecordingItem.STATUS_RECORDING, false, false, true
        ));
        assertFalse(RecordingRecoveryPolicy.mayBeginFinalization(
            RecordingItem.STATUS_RECOVERABLE, false, false, false
        ));
        assertFalse(RecordingRecoveryPolicy.mayBeginFinalization(
            RecordingItem.STATUS_RECORDING, true, false, false
        ));
        assertFalse(RecordingRecoveryPolicy.mayBeginFinalization(
            RecordingItem.STATUS_RECORDING, false, true, false
        ));
        assertFalse(RecordingRecoveryPolicy.mayRestartAfterCleanExit(
            RecordingItem.STATUS_RECOVERABLE, true, false, false, false
        ));
        assertFalse(RecordingRecoveryPolicy.mayRestartAfterCleanExit(
            RecordingItem.STATUS_PAUSED_BY_USER, true, false, false, false
        ));
        assertFalse(RecordingRecoveryPolicy.mayRestartAfterCleanExit(
            RecordingItem.STATUS_STOPPED_BY_USER, true, true, false, false
        ));
        assertFalse(RecordingRecoveryPolicy.mayRestartAfterCleanExit(
            RecordingItem.STATUS_RECORDING, true, false, true, false
        ));
        assertTrue(RecordingRecoveryPolicy.mayRestartAfterCleanExit(
            RecordingItem.STATUS_RECORDING, true, false, false, false
        ));
        assertTrue(RecordingRecoveryPolicy.mayBeginFinalization(
            RecordingItem.STATUS_RECORDING, false, false, false
        ));
    }

    @Test
    public void failedStartupRollbackRequiresOwnedEmptyCurrentChunkAndNonConflictingState() {
        assertTrue(RecordingRecoveryPolicy.shouldRollbackFailedStartupChunk(
            RecordingItem.STATUS_RECORDING, true, true, false, false
        ));
        assertFalse(RecordingRecoveryPolicy.shouldRollbackFailedStartupChunk(
            RecordingItem.STATUS_RECORDING, false, true, false, false
        ));
        assertFalse(RecordingRecoveryPolicy.shouldRollbackFailedStartupChunk(
            RecordingItem.STATUS_RECORDING, true, false, false, false
        ));
        assertFalse(RecordingRecoveryPolicy.shouldRollbackFailedStartupChunk(
            RecordingItem.STATUS_RECORDING, true, true, true, false
        ));
        assertFalse(RecordingRecoveryPolicy.shouldRollbackFailedStartupChunk(
            RecordingItem.STATUS_RECORDING, true, true, false, true
        ));
        assertFalse(RecordingRecoveryPolicy.shouldRollbackFailedStartupChunk(
            RecordingItem.STATUS_PAUSED_BY_USER, true, true, false, false
        ));
    }

    @Test
    public void removingFailedChunkNeverRemovesOriginalOrOtherSegments() {
        RecordingItem recording = new RecordingItem(
            "channel-id", "Channel", "https://example.test/channel", "video-id",
            "https://example.test/video", "Title", "480p", "/tmp/original.ts", "/tmp/final.mp4"
        );
        recording.addTempChunkPath("/tmp/original.part2.ts");
        recording.addTempChunkPath("/tmp/original.part3.ts");

        assertTrue(recording.removeTempChunkPath("/tmp/original.part3.ts"));
        assertFalse(recording.removeTempChunkPath("/tmp/original.ts"));

        assertEquals(java.util.Arrays.asList("/tmp/original.ts", "/tmp/original.part2.ts"),
            recording.getTempSegmentPaths());
    }

    @Test
    public void staleCompletionCallbackCannotClaimFinalizationAfterUnknownPreservation() throws Exception {
        RecordingOperationLocks lifecycleLocks = new RecordingOperationLocks();
        AtomicReference<String> persistedStatus = new AtomicReference<>(RecordingItem.STATUS_RECORDING);
        AtomicBoolean finalizing = new AtomicBoolean(false);
        AtomicInteger finalizationActions = new AtomicInteger();
        CountDownLatch callbackReadStaleState = new CountDownLatch(1);
        CountDownLatch allowCallbackToContinue = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<?> callback = executor.submit(() -> {
            String staleSnapshot = persistedStatus.get();
            assertEquals(RecordingItem.STATUS_RECORDING, staleSnapshot);
            callbackReadStaleState.countDown();
            await(allowCallbackToContinue);
            lifecycleLocks.withRecordingLock("recording-id", () -> {
                String latestStatus = persistedStatus.get();
                if (RecordingRecoveryPolicy.mayBeginFinalization(
                    latestStatus, false, finalizing.get(), false
                ) && finalizing.compareAndSet(false, true)) {
                    finalizationActions.incrementAndGet();
                }
                return null;
            });
        });

        assertTrue(callbackReadStaleState.await(2, TimeUnit.SECONDS));
        lifecycleLocks.withRecordingLock("recording-id", () -> {
            if (RecordingRecoveryPolicy.mayPreserveUnknownStatus(
                persistedStatus.get(), false, finalizing.get(), false
            )) {
                persistedStatus.set(RecordingItem.STATUS_RECOVERABLE);
            }
            return null;
        });
        allowCallbackToContinue.countDown();
        callback.get(2, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(RecordingItem.STATUS_RECOVERABLE, persistedStatus.get());
        assertEquals(0, finalizationActions.get());
    }

    @Test
    public void competingMonitorWorkCreatesOnlyOneRecordingForSameVideo() throws Exception {
        RecordingOperationLocks sharedLocks = new RecordingOperationLocks();
        AtomicBoolean itemExists = new AtomicBoolean(false);
        AtomicInteger createdItems = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> first = executor.submit(() -> createIfAbsent(sharedLocks, itemExists, createdItems, ready, start));
        Future<?> second = executor.submit(() -> createIfAbsent(sharedLocks, itemExists, createdItems, ready, start));
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        start.countDown();
        first.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(1, createdItems.get());
    }

    private static void createIfAbsent(
        RecordingOperationLocks locks,
        AtomicBoolean itemExists,
        AtomicInteger createdItems,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        ready.countDown();
        await(start);
        locks.withVideoLock("video-id", () -> {
            if (!itemExists.get()) {
                itemExists.set(true);
                createdItems.incrementAndGet();
            }
            return null;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
