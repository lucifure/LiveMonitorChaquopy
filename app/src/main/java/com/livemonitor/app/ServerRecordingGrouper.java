package com.livemonitor.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Groups the per-file rows returned by GET /recordings into recording cards. */
public final class ServerRecordingGrouper {
    public enum Type { IN_PROGRESS, SINGLE_FILE, PARTIAL }

    public static final class RemoteFile {
        public final String name;
        public final long size;
        public final long modified;
        public final boolean inProgress;

        public RemoteFile(String name, long size, long modified, boolean inProgress) {
            this.name = name == null ? "" : name;
            this.size = Math.max(0L, size);
            this.modified = Math.max(0L, modified);
            this.inProgress = inProgress;
        }
    }

    public static final class Group {
        public final String baseName;
        public final Type type;
        public final List<RemoteFile> files;
        public final List<RemoteFile> mediaFiles;
        public final long totalSize;
        public final long modified;

        private Group(String baseName, Type type, List<RemoteFile> files,
                      List<RemoteFile> mediaFiles, long totalSize, long modified) {
            this.baseName = baseName;
            this.type = type;
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            this.mediaFiles = Collections.unmodifiableList(new ArrayList<>(mediaFiles));
            this.totalSize = totalSize;
            this.modified = modified;
        }

        public boolean isInProgress() { return type == Type.IN_PROGRESS; }
        public boolean isSingleFile() { return type == Type.SINGLE_FILE; }
        public boolean isPartial() { return type == Type.PARTIAL; }
    }

    private static final class Bucket {
        String baseName;
        boolean inProgress;
        boolean partialMarker;
        long size;
        long modified;
        final List<RemoteFile> files = new ArrayList<>();
        final List<RemoteFile> mediaFiles = new ArrayList<>();
    }

    private ServerRecordingGrouper() {}

    public static List<Group> group(List<RemoteFile> input) {
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        if (input != null) {
            for (RemoteFile file : input) {
                if (file == null || file.name.trim().isEmpty()) continue;
                NameParts parts = parseName(file.name);
                Bucket bucket = buckets.get(parts.key);
                if (bucket == null) {
                    bucket = new Bucket();
                    bucket.baseName = parts.baseName;
                    buckets.put(parts.key, bucket);
                }
                bucket.files.add(file);
                bucket.inProgress |= file.inProgress;
                bucket.modified = Math.max(bucket.modified, file.modified);
                if (!parts.metadata) {
                    bucket.mediaFiles.add(file);
                    bucket.size += file.size;
                    bucket.partialMarker |= parts.partialMarker;
                }
            }
        }

        List<Group> groups = new ArrayList<>();
        for (Bucket bucket : buckets.values()) {
            // A metadata-only row is not a recording card unless it signals an active recording.
            if (bucket.mediaFiles.isEmpty() && !bucket.inProgress) continue;
            Type type;
            if (bucket.inProgress) {
                type = Type.IN_PROGRESS;
            } else if (bucket.mediaFiles.size() == 1 && !bucket.partialMarker && isFinishedVideo(bucket.mediaFiles.get(0).name)) {
                type = Type.SINGLE_FILE;
            } else {
                type = Type.PARTIAL;
            }
            groups.add(new Group(bucket.baseName, type, bucket.files, bucket.mediaFiles,
                bucket.size, bucket.modified));
        }
        groups.sort((left, right) -> Long.compare(right.modified, left.modified));
        return groups;
    }

    private static final class NameParts {
        String key;
        String baseName;
        boolean metadata;
        boolean partialMarker;
    }

    private static NameParts parseName(String name) {
        String value = name.trim();
        NameParts result = new NameParts();
        result.metadata = value.toLowerCase(Locale.US).endsWith(".ytdl");
        if (result.metadata) value = value.substring(0, value.length() - 5);

        String lower = value.toLowerCase(Locale.US);
        if (lower.endsWith(".part")) {
            result.partialMarker = true;
            value = value.substring(0, value.length() - 5);
        } else if (lower.matches(".*\\.part-frag[0-9]+$")) {
            result.partialMarker = true;
            value = value.replaceFirst("(?i)\\.part-frag[0-9]+$", "");
        }

        int extensionStart = value.lastIndexOf('.');
        if (extensionStart > 0) value = value.substring(0, extensionStart);
        if (value.matches("(?i).*\\.f[0-9]+$")) {
            result.partialMarker = true;
            value = value.replaceFirst("(?i)\\.f[0-9]+$", "");
        }
        if (value.isEmpty()) value = name.trim();
        result.baseName = value;
        result.key = value.toLowerCase(Locale.US);
        return result;
    }

    private static boolean isFinishedVideo(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".ts");
    }
}
