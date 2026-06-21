package com.livemonitor.app;

import java.util.ArrayList;
import java.util.List;

/** Splits copyable logs into conservative clipboard chunks without losing line boundaries. */
public final class ClipboardLogSplitter {
    public static final int MAX_CLIPBOARD_CHARS_PER_PART = 20_000;

    private ClipboardLogSplitter() {}

    public static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && i < text.length() - 1) {
                lines++;
            }
        }
        return lines;
    }

    public static List<Part> split(String text) {
        List<Part> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return parts;
        }

        StringBuilder current = new StringBuilder();
        int currentStartLine = 1;
        int currentEndLine = 0;
        int lineNumber = 1;
        int start = 0;

        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline >= 0 ? newline + 1 : text.length();
            String line = text.substring(start, end);

            if (current.length() > 0
                && current.length() + line.length() > MAX_CLIPBOARD_CHARS_PER_PART) {
                parts.add(new Part(current.toString(), currentStartLine, currentEndLine));
                current.setLength(0);
                currentStartLine = lineNumber;
            }

            current.append(line);
            currentEndLine = lineNumber;
            start = end;
            lineNumber++;
        }

        if (current.length() > 0) {
            parts.add(new Part(current.toString(), currentStartLine, currentEndLine));
        }

        StringBuilder rebuilt = new StringBuilder();
        for (Part part : parts) {
            rebuilt.append(part.text);
        }
        if (!text.contentEquals(rebuilt)) {
            throw new IllegalStateException("Clipboard log split failed reconstruction check.");
        }

        return parts;
    }

    public static final class Part {
        private final String text;
        private final int startLine;
        private final int endLine;

        private Part(String text, int startLine, int endLine) {
            this.text = text == null ? "" : text;
            this.startLine = Math.max(1, startLine);
            this.endLine = Math.max(this.startLine, endLine);
        }

        public String getText() { return text; }
        public int getStartLine() { return startLine; }
        public int getEndLine() { return endLine; }
        public int length() { return text.length(); }
    }
}
