package com.livemonitor.app;

/** Pure decisions for distinguishing missing provider documents from unknown results. */
final class ContentUriExistencePolicy {
    private ContentUriExistencePolicy() {}

    static boolean existsFromQuery(boolean cursorReturned, boolean rowFound,
                                   boolean sizeKnown, long size) {
        if (!cursorReturned || !rowFound) return false;
        if (!sizeKnown) return true;
        return size > 0L;
    }

    static boolean existsAfterQueryError(RuntimeException error) {
        return !(error instanceof IllegalArgumentException);
    }
}
