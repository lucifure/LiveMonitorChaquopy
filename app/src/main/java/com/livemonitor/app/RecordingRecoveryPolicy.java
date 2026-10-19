package com.livemonitor.app;

/** Pure lifecycle decisions for preserving and resuming interrupted recordings. */
final class RecordingRecoveryPolicy {
    private RecordingRecoveryPolicy() {}

    static boolean mayPreserveUnknownStatus(
        String recordingStatus,
        boolean userHalted,
        boolean finalizing,
        boolean finalized
    ) {
        return RecordingItem.STATUS_RECORDING.equals(recordingStatus)
            && !userHalted
            && !finalizing
            && !finalized;
    }

    static boolean shouldIgnoreCompletionCallback(String recordingStatus) {
        return RecordingItem.STATUS_RECOVERABLE.equals(recordingStatus);
    }

    static boolean mayRestartAfterCleanExit(
        String recordingStatus,
        boolean channelMonitoring,
        boolean userHalted,
        boolean finalizing,
        boolean finalized
    ) {
        return RecordingItem.STATUS_RECORDING.equals(recordingStatus)
            && channelMonitoring
            && !userHalted
            && !finalizing
            && !finalized;
    }

    static boolean mayBeginFinalization(
        String recordingStatus,
        boolean userHalted,
        boolean finalizing,
        boolean finalized
    ) {
        return !userHalted
            && !finalizing
            && !finalized
            && !shouldIgnoreCompletionCallback(recordingStatus)
            && !RecordingItem.STATUS_PAUSED_BY_USER.equals(recordingStatus)
            && !RecordingItem.STATUS_STOPPED_BY_USER.equals(recordingStatus)
            && !RecordingItem.STATUS_CONVERTING.equals(recordingStatus)
            && !RecordingItem.STATUS_COPYING.equals(recordingStatus)
            && !RecordingItem.STATUS_COMPLETED.equals(recordingStatus)
            && !RecordingItem.STATUS_FAILED.equals(recordingStatus);
    }

    static boolean shouldFinalizeAfterStreamStatus(String streamEndStatus) {
        return "CONFIRMED_ENDED".equals(streamEndStatus);
    }

    static boolean shouldDeferNewRecording(boolean matchingRecoverableExists) {
        return matchingRecoverableExists;
    }

    static boolean containsTempSegmentPath(RecordingItem recording, String path) {
        if (recording == null || path == null || path.trim().isEmpty()) {
            return false;
        }
        for (String segmentPath : recording.getTempSegmentPaths()) {
            if (path.equals(segmentPath)) {
                return true;
            }
        }
        return false;
    }

    static boolean shouldResumeRecoverable(
        String recordingStatus,
        boolean sameVideo,
        boolean hasTempTsPath
    ) {
        return RecordingItem.STATUS_RECOVERABLE.equals(recordingStatus)
            && sameVideo
            && hasTempTsPath;
    }

    static boolean shouldRollbackFailedStartupChunk(
        String recordingStatus,
        boolean newlySelectedChunk,
        boolean candidateIsCurrentSegment,
        boolean candidateHasData,
        boolean startupOutcomeAmbiguous
    ) {
        return RecordingItem.STATUS_RECORDING.equals(recordingStatus)
            && newlySelectedChunk
            && candidateIsCurrentSegment
            && !candidateHasData
            && !startupOutcomeAmbiguous;
    }
}
