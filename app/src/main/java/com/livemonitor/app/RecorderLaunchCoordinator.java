package com.livemonitor.app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Coordinates short recorder-start handoffs with recording lifecycle changes. */
final class RecorderLaunchCoordinator {
    interface Handle {
        void cancel();
    }

    interface Starter<H extends Handle> {
        H start() throws Exception;
    }

    interface StartupFailureHandler {
        void onFailure(Exception failure, boolean starterInvoked);
    }

    interface OutsideGateOperation<T> {
        T run() throws Exception;
    }

    private final RecordingOperationLocks locks;
    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock launchShutdownGate = new ReentrantReadWriteLock(true);
    private final Object generationStateLock = new Object();
    private volatile boolean shutdownStarted;
    private volatile long serviceGeneration;
    private long shutdownCleanupGeneration = -1L;

    RecorderLaunchCoordinator(RecordingOperationLocks locks) {
        this.locks = locks;
    }

    <T> T withRecordingLock(String recordingId, Supplier<T> operation) {
        return locks.withRecordingLock(recordingId, operation);
    }

    <T> T outsideGate(OutsideGateOperation<T> operation) throws Exception {
        return operation.run();
    }

    long beginServiceGeneration() {
        boolean interrupted = false;
        while (true) {
            synchronized (generationStateLock) {
                while (shutdownCleanupGeneration == serviceGeneration) {
                    try {
                        generationStateLock.wait();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }

            launchShutdownGate.writeLock().lock();
            try {
                synchronized (generationStateLock) {
                    // Shutdown may have started after the wait above but
                    // before this caller acquired the write side.
                    if (shutdownCleanupGeneration == serviceGeneration) {
                        continue;
                    }
                    serviceGeneration++;
                    shutdownStarted = false;
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return serviceGeneration;
                }
            } finally {
                launchShutdownGate.writeLock().unlock();
            }
        }
    }

    boolean beginShutdown() {
        return beginShutdown(serviceGeneration);
    }

    boolean beginShutdown(long expectedGeneration) {
        launchShutdownGate.writeLock().lock();
        try {
            synchronized (generationStateLock) {
                if (serviceGeneration != expectedGeneration) {
                    return false;
                }
                if (shutdownStarted) {
                    return shutdownCleanupGeneration == expectedGeneration;
                }
                // Shutdown wins only after all handoffs that already held the
                // read side have registered their handles. Once published,
                // no later handoff can pass the gate until a new generation.
                // Keep that generation closed until service cleanup, including
                // cancellation outside this gate, explicitly completes.
                shutdownStarted = true;
                shutdownCleanupGeneration = expectedGeneration;
                return true;
            }
        } finally {
            launchShutdownGate.writeLock().unlock();
        }
    }

    void completeShutdown(long expectedGeneration) {
        synchronized (generationStateLock) {
            if (shutdownCleanupGeneration == expectedGeneration) {
                shutdownCleanupGeneration = -1L;
                generationStateLock.notifyAll();
            }
        }
    }

    boolean isShutdownStarted() {
        return shutdownStarted;
    }

    boolean isGenerationActive(long expectedGeneration) {
        // A hint for early worker exit only. The authoritative generation
        // check is repeated under the launch gate in startIfEligible().
        return serviceGeneration == expectedGeneration && !shutdownStarted;
    }

    long currentGeneration() {
        return serviceGeneration;
    }

    java.util.Set<String> recordingIdsWithHandles() {
        return new java.util.HashSet<>(handles.keySet());
    }

    void cancelOutsideGate(Handle handle) {
        if (handle != null) {
            handle.cancel();
        }
    }

    <H extends Handle> H startIfEligible(
        String recordingId,
        BooleanSupplier latestStateIsEligible,
        Runnable persistStartingState,
        Starter<H> starter
    ) throws Exception {
        return startIfEligible(recordingId, latestStateIsEligible, persistStartingState, starter, null);
    }

    <H extends Handle> H startIfEligible(
        String recordingId,
        BooleanSupplier latestStateIsEligible,
        Runnable persistStartingState,
        Starter<H> starter,
        StartupFailureHandler failureHandler
    ) throws Exception {
        return startIfEligible(
            serviceGeneration, recordingId, latestStateIsEligible, persistStartingState, starter, failureHandler
        );
    }

    <H extends Handle> H startIfEligible(
        long expectedGeneration,
        String recordingId,
        BooleanSupplier latestStateIsEligible,
        Runnable persistStartingState,
        Starter<H> starter,
        StartupFailureHandler failureHandler
    ) throws Exception {
        launchShutdownGate.readLock().lock();
        try {
            if (serviceGeneration != expectedGeneration || shutdownStarted) {
                return null;
            }
            return locks.withRecordingLock(recordingId, () -> {
                if (handles.containsKey(recordingId) || !latestStateIsEligible.getAsBoolean()) {
                    return null;
                }

                boolean starterInvoked = false;
                try {
                    persistStartingState.run();
                    starterInvoked = true;
                    H handle = starter.start();
                    if (handle == null) {
                        throw new IllegalStateException("Recorder start returned no cancellable handle.");
                    }
                    handles.put(recordingId, handle);
                    return handle;
                } catch (Exception e) {
                    if (failureHandler != null) {
                        try {
                            failureHandler.onFailure(e, starterInvoked);
                        } catch (RuntimeException cleanupFailure) {
                            e.addSuppressed(cleanupFailure);
                        }
                    }
                    throw new StartFailure(e);
                }
            });
        } catch (StartFailure e) {
            throw (Exception) e.getCause();
        } finally {
            launchShutdownGate.readLock().unlock();
        }
    }

    Handle removeHandle(String recordingId) {
        return locks.withRecordingLock(recordingId, () -> handles.remove(recordingId));
    }

    void removeHandleIfSame(String recordingId, Handle expected) {
        if (expected == null) {
            return;
        }
        locks.withRecordingLock(recordingId, () -> handles.remove(recordingId, expected));
    }

    boolean removeHandleIfSameWhileLocked(String recordingId, Handle expected) {
        return expected != null && handles.remove(recordingId, expected);
    }

    Handle removeHandleWhileLocked(String recordingId) {
        return handles.remove(recordingId);
    }

    boolean hasHandle(String recordingId) {
        return handles.containsKey(recordingId);
    }

    static final class CompletionBarrier {
        private boolean released;
        private java.util.List<Runnable> pending = new java.util.ArrayList<>();

        synchronized void dispatch(Runnable completion) {
            if (!released) {
                pending.add(completion);
                return;
            }
            completion.run();
        }

        void release() {
            java.util.List<Runnable> completions;
            synchronized (this) {
                released = true;
                completions = pending;
                pending = new java.util.ArrayList<>();
            }
            for (Runnable completion : completions) {
                completion.run();
            }
        }
    }

    private static final class StartFailure extends RuntimeException {
        private StartFailure(Exception cause) {
            super(cause);
        }
    }
}
