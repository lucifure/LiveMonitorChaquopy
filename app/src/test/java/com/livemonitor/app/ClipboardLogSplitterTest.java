package com.livemonitor.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class ClipboardLogSplitterTest {
    @Test
    public void splitRebuildsOriginalAndKeepsWholeLines() {
        StringBuilder original = new StringBuilder();
        int lineCount = 5000;
        for (int i = 1; i <= lineCount; i++) {
            original.append("line-").append(i).append(" ");
            for (int j = 0; j < 12; j++) {
                original.append("payload").append(j).append(" ");
            }
            original.append('\n');
        }

        List<ClipboardLogSplitter.Part> parts = ClipboardLogSplitter.split(original.toString());

        assertTrue("test log should require multiple clipboard parts", parts.size() > 1);
        StringBuilder rebuilt = new StringBuilder();
        int expectedStartLine = 1;
        for (ClipboardLogSplitter.Part part : parts) {
            assertEquals(expectedStartLine, part.getStartLine());
            assertTrue(part.getEndLine() >= part.getStartLine());
            assertTrue("part should end on a line boundary", part.getText().endsWith("\n"));
            assertTrue(
                "regular parts should stay within the safe clipboard size",
                part.length() <= ClipboardLogSplitter.MAX_CLIPBOARD_CHARS_PER_PART
            );
            rebuilt.append(part.getText());
            expectedStartLine = part.getEndLine() + 1;
        }

        assertEquals(lineCount + 1, expectedStartLine);
        assertEquals(original.toString(), rebuilt.toString());
    }

    @Test
    public void splitDoesNotTruncateSingleLineLongerThanLimit() {
        StringBuilder longLine = new StringBuilder();
        for (int i = 0; i < ClipboardLogSplitter.MAX_CLIPBOARD_CHARS_PER_PART + 25; i++) {
            longLine.append('x');
        }
        String original = "before\n" + longLine + "\nafter";

        List<ClipboardLogSplitter.Part> parts = ClipboardLogSplitter.split(original);

        StringBuilder rebuilt = new StringBuilder();
        boolean sawOversizedLinePart = false;
        for (ClipboardLogSplitter.Part part : parts) {
            rebuilt.append(part.getText());
            if (part.length() > ClipboardLogSplitter.MAX_CLIPBOARD_CHARS_PER_PART) {
                sawOversizedLinePart = true;
                assertFalse("oversized line part should not be split mid-line", part.getText().endsWith("x"));
            }
        }

        assertTrue("a single oversized line should be preserved whole", sawOversizedLinePart);
        assertEquals(original, rebuilt.toString());
    }

    @Test
    public void countLinesMatchesDisplayedBoundaries() {
        assertEquals(0, ClipboardLogSplitter.countLines(""));
        assertEquals(1, ClipboardLogSplitter.countLines("one"));
        assertEquals(2, ClipboardLogSplitter.countLines("one\ntwo"));
        assertEquals(2, ClipboardLogSplitter.countLines("one\ntwo\n"));
    }
}
