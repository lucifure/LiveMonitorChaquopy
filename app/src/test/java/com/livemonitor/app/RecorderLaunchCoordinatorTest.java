package com.livemonitor.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import java.io.File;

public class RecorderLaunchCoordinatorTest {
    @Test
    public void conflictingTransitionBeforeStartupPreventsLaunchAndPreservesPaths() throws Exception {
        RecorderLaunchCoordinator coordinator = new RecorderLaunchCoordinator(new RecordingOperationLocks());
        AtomicReference<String> status = new AtomicReference<>(RecordingItem.STATUS_RECORDING);
        File fragment = File.createTempFile("rejected-recorder-", ".ts");
        AtomicInteger starts = new AtomicInteger();

        coordinator.withRecordingLock("recording-1", () -> {
            status.set(RecordingItem.STATUS_RECOVERABLE);
            return null;
        });

        RecorderLaunchCoordinator.Handle started = coordinator.startIfEligible(
            "recording-1",
            () -> RecordingItem.STATUS_RECORDING.equals(status.get()),
            () -> status.set(RecordingItem.STATUS_RECORDING),
            () -> {
                starts.incrementAndGet();
                fragment.delete();
                return new TestHandle();
            }
        );

        try {
            assertNull(started);
            assertEquals(0, starts.get());
            assertEquals(RecordingItem.STATUS_RECOVERABLE, status.get());
            assertTrue("Rejected startup must leave existing TS fragments untouched.", fragment.exists());
        } finally {
            fragment.delete();
        }
    }

    @Test
    public void conflictingTransitionWaitsForRegisteredHandleThenCancelsItOutsideGate() throws Exception {
        RecorderLaunchCoordinator coordinator = new RecorderLaunchCoordinator(new RecordingOperationLocks());
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch allowStarterReturn = new CountDownLatch(1);
        CountDownLatch transitionEntered = new CountDownLatch(1);
        AtomicReference<String> status = new AtomicReference<>(RecordingItem.STATUS_RECORDING);
        AtomicReference<TestHandle> startedHandle = new AtomicReference<>();
        AtomicBoolean transitionCommitted = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> launch = pool.submit(() -> {
                try {
                    coordinator.startIfEligible(
                        "recording-2",
                        () -> RecordingItem.STATUS_RECORDING.equals(status.get()),
                        () -> { },
                        () -> {
                            starterEntered.countDown();
                            assertTrue(allowStarterReturn.await(2, TimeUnit.SECONDS));
                            TestHandle handle = new TestHandle();
                            startedHandle.set(handle);
                            return handle;
                        }
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(starterEntered.await(2, TimeUnit.SECONDS));

            Future<?> transition = pool.submit(() -> coordinator.withRecordingLock("recording-2", () -> {
                transitionEntered.countDown();
                status.set(RecordingItem.STATUS_RECOVERABLE);
                transitionCommitted.set(true);
                return coordinator.removeHandleWhileLocked("recording-2");
            }));
            assertFalse(transitionEntered.await(100, TimeUnit.MILLISECONDS));

            allowStarterReturn.countDown();
            launch.get(2, TimeUnit.SECONDS);
            RecorderLaunchCoordinator.Handle captured = (RecorderLaunchCoordinator.Handle) transition.get(
                2, TimeUnit.SECONDS
            );
            assertNotNull(captured);
            assertEquals(RecordingItem.STATUS_RECOVERABLE, status.get());

            assertTrue(transitionCommitted.get());
            coordinator.cancelOutsideGate(captured);
            assertTrue(startedHandle.get().cancelled.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void userStopAfterStartupAcknowledgmentCancelsExactHandleAndBlocksAnotherStart() throws Exception {
        RecorderLaunchCoordinator coordinator = new RecorderLaunchCoordinator(new RecordingOperationLocks());
        AtomicReference<String> status = new AtomicReference<>(RecordingItem.STATUS_RECORDING);
        TestHandle handle = coordinator.startIfEligible(
            "recording-stop",
            () -> RecordingItem.STATUS_RECORDING.equals(status.get()),
            () -> { },
            TestHandle::new
        );

        RecorderLaunchCoordinator.Handle captured = coordinator.withRecordingLock("recording-stop", () -> {
            status.set(RecordingItem.STATUS_STOPPED_BY_USER);
            return coordinator.removeHandleWhileLocked("recording-stop");
        });
        coordinator.cancelOutsideGate(captured);

        RecorderLaunchCoordinator.Handle restarted = coordinator.startIfEligible(
            "recording-stop",
            () -> RecordingItem.STATUS_RECORDING.equals(status.get()),
            () -> status.set(RecordingItem.STATUS_RECORDING),
            TestHandle::new
        );
        assertTrue(handle.cancelled.get());
        assertNull(restarted);
        assertEquals(RecordingItem.STATUS_STOPPED_BY_USER, status.get());
    }

    @Test
    public void immediateCompletionIsBufferedUntilHandleRegistration() throws Exception {
        RecorderLaunchCoordinator coordinator = new RecorderLaunchCoordinator(new RecordingOperationLocks());
        RecorderLaunchCoordinator.CompletionBarrier barrier = new RecorderLaunchCoordinator.CompletionBarrier();
        AtomicBoolean finalized = new AtomicBoolean();
        AtomicBoolean registered = new AtomicBoolean();

        TestHandle handle = coordinator.startIfEligible(
            "recording-3",
            () -> true,
            () -> { },
            () -> {
                barrier.dispatch(() -> finalized.set(registered.get()));
                assertFalse(finalized.get());
                return new TestHandle();
            }
        );
        assertNotNull(handle);
        registered.set(true);
        barrier.release();
        assertTrue(finalized.get());
    }

    @Test
    public void startupFailureLeavesNoHandleAndDoesNotOverwriteNewerPause() throws Exception {
        RecorderLaunchCoordinator coordinator = new RecorderLaunchCoordinator(new RecordingOperationLocks());
        AtomicReference<String> status = new AtomicReference<>(RecordingItem.STATUS_RECORDING);
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch allowFailure = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> startup = pool.submit(() -> {
                try {
                    coordinator.startIfEligible(
                        "recording-4",
                        () -> RecordingItem.STATUS_RECORDING.equals(status.get()),
                        () -> { },
                        () -> {
                            starterEntered.countDown();
                            assertTrue(allowFailure.await(2, TimeUnit.SECONDS));
                            throw new IllegalStateException("process start rejected");
                        }
                    );
                } catch (Exception expected) {
                    // The failed starter owns no handle after the gate releases.
                }
            });
            assertTrue(starterEntered.await(2, TimeUnit.SECONDS));
            Future<?> pause = pool.submit(() -> coordinator.withRecordingLock("recording-4", () -> {
                status.set(RecordingItem.STATUS_PAUSED_BY_USER);
                return null;
            }));
            allowFailure.countDown();
            startup.get(2, TimeUnit.SECONDS);
            pause.get(2, TimeUnit.SECONDS);
            assertFalse(coordinator.hasHandle("recording-4"));
            assertEquals(RecordingItem.STATUS_PAUSED_BY_USER, status.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void waitingAndCancellationDoNotRetainLifecycleGate() throws Exception {
        RecorderLaunchCoordinator coordinator = new RecorderLaunchCoordinator(new RecordingOperationLocks());
        CountDownLatch waitEntered = new CountDownLatch(1);
        CountDownLatch releaseWait = new CountDownLatch(1);
        CountDownLatch cancelEntered = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            TestHandle handle = coordinator.startIfEligible(
                "recording-5", () -> true, () -> { }, TestHandle::new
            );
            Future<?> waiter = pool.submit(() -> {
                try {
                    coordinator.outsideGate(() -> {
                        waitEntered.countDown();
                        releaseWait.await(2, TimeUnit.SECONDS);
                        return null;
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Future<?> canceller = pool.submit(() -> {
                coordinator.cancelOutsideGate(new TestHandle(() -> {
                    cancelEntered.countDown();
                    try {
                        releaseCancel.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            });
            assertTrue(waitEntered.await(2, TimeUnit.SECONDS));
            assertTrue(cancelEntered.await(2, TimeUnit.SECONDS));
            assertEquals("transition", coordinator.withRecordingLock("recording-5", () -> "transition"));
            releaseWait.countDown();
            releaseCancel.countDown();
            waiter.get(2, TimeUnit.SECONDS);
            canceller.get(2, TimeUnit.SECONDS);
            assertNotNull(handle);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void shutdownWaitsForStartupHandleAndPreservesLatestChunkPath() throws Exception {
        RecorderLaunchCoordinator coordinator = new RecorderLaunchCoordinator(new RecordingOperationLocks());
        RecordingItem initial = new RecordingItem(
            "channel-id", "Channel", "https://example.test/channel", "video-id",
            "https://example.test/video", "Title", "480p", "/tmp/original.ts", "/tmp/final.mp4"
        );
        initial.markRecording();
        AtomicReference<RecordingItem> persisted = new AtomicReference<>(initial);
        RecordingItem staleActiveSnapshot = RecordingItem.fromJson(initial.toJson());
        String recordingId = initial.getId();
        String newChunk = "/tmp/original.part2.ts";
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch allowStarterReturn = new CountDownLatch(1);
        CountDownLatch shutdownRequested = new CountDownLatch(1);
        AtomicReference<TestHandle> startedHandle = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<?> startup = pool.submit(() -> {
                try {
                    coordinator.startIfEligible(
                        recordingId,
                        () -> RecordingItem.STATUS_RECORDING.equals(persisted.get().getStatus()),
                        () -> {
                            RecordingItem latest = persisted.get();
                            latest.addTempChunkPath(newChunk);
                            persisted.set(latest);
                        },
                        () -> {
                            starterEntered.countDown();
                            assertTrue(allowStarterReturn.await(2, TimeUnit.SECONDS));
                            TestHandle handle = new TestHandle();
                            startedHandle.set(handle);
                            return handle;
                        }
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(starterEntered.await(2, TimeUnit.SECONDS));

            Future<?> shutdown = pool.submit(() -> {
                shutdownRequested.countDown();
                coordinator.beginShutdown();
                coordinator.withRecordingLock(recordingId, () -> {
                    RecordingItem latest = persisted.get();
                    if (RecordingItem.STATUS_RECORDING.equals(latest.getStatus())) {
                        latest.markStoppedBySystem("service shutdown");
                        persisted.set(latest);
                    }
                    return coordinator.removeHandleWhileLocked(recordingId);
                });
            });
            assertTrue(shutdownRequested.await(2, TimeUnit.SECONDS));
            assertFalse("Shutdown must wait for the startup handoff to register its handle.",
                shutdown.isDone());

            allowStarterReturn.countDown();
            startup.get(2, TimeUnit.SECONDS);
            RecorderLaunchCoordinator.Handle captured = (RecorderLaunchCoordinator.Handle) shutdown.get(
                2, TimeUnit.SECONDS
            );
            assertEquals(startedHandle.get(), captured);
            assertEquals(RecordingItem.STATUS_STOPPED_BY_SYSTEM, persisted.get().getStatus());
            assertTrue(persisted.get().getTempSegmentPaths().contains(newChunk));
            assertFalse(staleActiveSnapshot.getTempSegmentPaths().contains(newChunk));
            coordinator.cancelOutsideGate(captured);
            assertTrue(startedHandle.get().cancelled.get());

            AtomicInteger laterStarts = new AtomicInteger();
            assertNull(coordinator.startIfEligible(
                recordingId,
                () -> true,
                () -> { },
                () -> {
                    laterStarts.incrementAndGet();
                    return new TestHandle();
                }
            ));
            assertEquals(0, laterStarts.get());
            assertTrue(persisted.get().getTempSegmentPaths().contains(newChunk));
        } finally {
            allowStarterReturn.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void oldQueuedWorkerCannotStartAfterShutdownAndNewServiceGeneration() throws Exception {
        RecorderLaunchCoordinator coordinator = new RecorderLaunchCoordinator(new RecordingOperationLocks());
        long oldGeneration = coordinator.beginServiceGeneration();
        AtomicReference<String> persistedStatus = new AtomicReference<>(RecordingItem.STATUS_RECORDING);
        AtomicInteger starts = new AtomicInteger();
        CountDownLatch oldWorkerQueued = new CountDownLatch(1);
        CountDownLatch allowOldWorker = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RecorderLaunchCoordinator.Handle> oldWorker = executor.submit(() -> {
                oldWorkerQueued.countDown();
                assertTrue(allowOldWorker.await(2, TimeUnit.SECONDS));
                return coordinator.startIfEligible(
                    oldGeneration,
                    "recording-old-generation",
                    () -> RecordingItem.STATUS_RECORDING.equals(persistedStatus.get()),
                    () -> { },
                    () -> {
                        starts.incrementAndGet();
                        return new TestHandle();
                    },
                    null
                );
            });
            assertTrue(oldWorkerQueued.await(2, TimeUnit.SECONDS));

            assertTrue(coordinator.beginShutdown(oldGeneration));
            // Model the shutdown persistence write failing. The generation gate
            // must still reject the worker without relying on persisted status.
            assertEquals(RecordingItem.STATUS_RECORDING, persistedStatus.get());
            coordinator.completeShutdown(oldGeneration);
            long newGeneration = coordinator.beginServiceGeneration();
            assertTrue(newGeneration > oldGeneration);
            allowOldWorker.countDown();

            assertNull(oldWorker.get(2, TimeUnit.SECONDS));
            assertEquals(0, starts.get());
            assertEquals(RecordingItem.STATUS_RECORDING, persistedStatus.get());
            assertTrue(coordinator.isGenerationActive(newGeneration));
        } finally {
            allowOldWorker.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void newGenerationWaitsUntilOldShutdownCancellationCompletes() throws Exception {
        RecorderLaunchCoordinator coordinator = new RecorderLaunchCoordinator(new RecordingOperationLocks());
        long oldGeneration = coordinator.beginServiceGeneration();
        TestHandle oldHandle = coordinator.startIfEligible(
            oldGeneration, "same-recording-id", () -> true, () -> { }, TestHandle::new, null
        );
        assertNotNull(oldHandle);
        assertTrue(coordinator.beginShutdown(oldGeneration));

        CountDownLatch generationAttempting = new CountDownLatch(1);
        CountDownLatch newGenerationStarted = new CountDownLatch(1);
        AtomicBoolean oldCancellationFinished = new AtomicBoolean();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Long> newGeneration = pool.submit(() -> {
                generationAttempting.countDown();
                long generation = coordinator.beginServiceGeneration();
                newGenerationStarted.countDown();
                return generation;
            });
            assertTrue(generationAttempting.await(2, TimeUnit.SECONDS));

            // Model stopAll canceling the exact captured old-generation handle
            // outside lifecycle locks while startup remains closed.
            assertFalse(newGenerationStarted.await(100, TimeUnit.MILLISECONDS));
            RecorderLaunchCoordinator.Handle captured = coordinator.withRecordingLock(
                "same-recording-id", () -> coordinator.removeHandleWhileLocked("same-recording-id")
            );
            assertEquals(oldHandle, captured);
            coordinator.cancelOutsideGate(captured);
            oldCancellationFinished.set(oldHandle.cancelled.get());
            assertTrue(oldCancellationFinished.get());
            coordinator.completeShutdown(oldGeneration);

            long nextGeneration = newGeneration.get(2, TimeUnit.SECONDS);
            assertTrue(nextGeneration > oldGeneration);
            AtomicInteger starts = new AtomicInteger();
            RecorderLaunchCoordinator.Handle started = coordinator.startIfEligible(
                nextGeneration,
                "same-recording-id",
                () -> true,
                () -> { },
                () -> {
                    assertTrue("Old shutdown cancellation must finish before new startup.",
                        oldCancellationFinished.get());
                    starts.incrementAndGet();
                    return new TestHandle();
                },
                null
            );
            assertNotNull(started);
            assertEquals(1, starts.get());
            assertTrue(oldHandle.cancelled.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void startupFailureHandlerRollsBackEmptyNewChunkBeforeReleasingGate() throws Exception {
        RecorderLaunchCoordinator coordinator = new RecorderLaunchCoordinator(new RecordingOperationLocks());
        RecordingItem item = new RecordingItem(
            "channel-id", "Channel", "https://example.test/channel", "video-id",
            "https://example.test/video", "Title", "480p", "/tmp/original.ts", "/tmp/final.mp4"
        );
        item.markRecording();
        AtomicReference<RecordingItem> persisted = new AtomicReference<>(item);
        String candidate = "/tmp/original.part3.ts";

        try {
            coordinator.startIfEligible(
                item.getId(),
                () -> RecordingItem.STATUS_RECORDING.equals(persisted.get().getStatus()),
                () -> {
                    RecordingItem latest = persisted.get();
                    latest.addTempChunkPath(candidate);
                    persisted.set(latest);
                    throw new IllegalStateException("persistence failed before process startup");
                },
                () -> {
                    throw new AssertionError("Starter must not run when persisting the candidate fails.");
                },
                (failure, starterInvoked) -> coordinator.withRecordingLock(item.getId(), () -> {
                    RecordingItem latest = persisted.get();
                    if (RecordingRecoveryPolicy.shouldRollbackFailedStartupChunk(
                        latest.getStatus(), true,
                        candidate.equals(latest.getCurrentTempSegmentPath()),
                        false, starterInvoked
                    )) {
                        latest.removeTempChunkPath(candidate);
                    }
                    latest.markRecoverable(failure.getMessage());
                    persisted.set(latest);
                    return null;
                })
            );
        } catch (IllegalStateException expected) {
            assertEquals("persistence failed before process startup", expected.getMessage());
        }

        assertEquals(RecordingItem.STATUS_RECOVERABLE, persisted.get().getStatus());
        assertEquals(java.util.Collections.singletonList("/tmp/original.ts"),
            persisted.get().getTempSegmentPaths());
    }

    @Test
    public void newerPauseBeforeRollbackKeepsItsStatusAndChunkPath() throws Exception {
        RecorderLaunchCoordinator coordinator = new RecorderLaunchCoordinator(new RecordingOperationLocks());
        RecordingItem item = new RecordingItem(
            "channel-id", "Channel", "https://example.test/channel", "video-id",
            "https://example.test/video", "Title", "480p", "/tmp/original.ts", "/tmp/final.mp4"
        );
        item.addTempChunkPath("/tmp/original.part4.ts");
        item.markRecording();

        coordinator.withRecordingLock(item.getId(), () -> {
            item.markPausedByUser();
            return null;
        });
        coordinator.withRecordingLock(item.getId(), () -> {
            if (RecordingRecoveryPolicy.shouldRollbackFailedStartupChunk(
                item.getStatus(), true, true, false, false
            )) {
                item.removeTempChunkPath("/tmp/original.part4.ts");
                item.markRecoverable("late start failure");
            }
            return null;
        });

        assertEquals(RecordingItem.STATUS_PAUSED_BY_USER, item.getStatus());
        assertTrue(item.getTempSegmentPaths().contains("/tmp/original.part4.ts"));
    }

    private static final class TestHandle implements RecorderLaunchCoordinator.Handle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Runnable cancellation;

        private TestHandle() {
            this(null);
        }

        private TestHandle(Runnable cancellation) {
            this.cancellation = cancellation;
        }

        @Override
        public void cancel() {
            if (cancellation != null) {
                cancellation.run();
            }
            cancelled.set(true);
        }
    }
}
