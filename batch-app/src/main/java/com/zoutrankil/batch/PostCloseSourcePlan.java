package com.zoutrankil.batch;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Immutable, content-addressed universe plan consumed before a post-close DataReady check. */
public final class PostCloseSourcePlan {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final Set<String> REQUIRED = requiredSources();
    private static Set<String> requiredSources() {
        var required = new TreeSet<>(PostCloseGraph.CORE_TABLES);
        required.add("exchange_calendar");
        return Collections.unmodifiableSet(required);
    }

    public record Source(String dataset, String definitionVersion, Set<String> expectedCodes, String universeVersion,
                         String revision, String supersedes, String revisionReason) {
        public Source {
            if (dataset == null || !REQUIRED.contains(dataset)) throw new IllegalArgumentException("Unregistered core source: " + dataset);
            if (!SourceContract.load(dataset).version().equals(definitionVersion))
                throw new IllegalArgumentException("Source definition version mismatch: " + dataset);
            expectedCodes = expectedCodes == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(expectedCodes));
            if (universeVersion == null || universeVersion.isBlank() || universeVersion.length() > 256)
                throw new IllegalArgumentException("A bounded universeVersion is required for " + dataset);
            if (revision == null || revision.isBlank() || revision.length() > 128)
                throw new IllegalArgumentException("A bounded source revision is required for " + dataset);
            if ("0".equals(revision)) {
                if (supersedes != null || revisionReason != null) throw new IllegalArgumentException("Initial source revision cannot supersede another instance");
            } else if (supersedes == null || !supersedes.matches("[a-f0-9]{64}") || revisionReason == null || revisionReason.isBlank()) {
                throw new IllegalArgumentException("Revised source requires predecessor identity and reason");
            }
            // Reuse the source contract's code, scope, and date validation before any network request.
            new SourceCollector.Request(dataset, LocalDate.of(2000, 1, 1), expectedCodes, universeVersion, null);
        }
    }

    public record Manifest(int schemaVersion, LocalDate logicalDate, String calendarVersion, String zone,
                          List<Source> sources) {
        public Manifest {
            if (schemaVersion != 1 || logicalDate == null || calendarVersion == null || calendarVersion.isBlank()
                    || zone == null || zone.isBlank()) throw new IllegalArgumentException("Invalid post-close source plan identity");
            java.time.ZoneId.of(zone);
            sources = List.copyOf(sources == null ? List.of() : sources);
            var seen = new TreeSet<String>();
            for (Source source : sources) if (!seen.add(source.dataset())) throw new IllegalArgumentException("Duplicate source in plan: " + source.dataset());
            if (!seen.equals(REQUIRED)) {
                var missing = new TreeSet<>(REQUIRED); missing.removeAll(seen);
                var unexpected = new TreeSet<>(seen); unexpected.removeAll(REQUIRED);
                throw new IllegalArgumentException("Core source plan must contain exactly the required datasets; missing=" + missing + ", unexpected=" + unexpected);
            }
        }
    }

    private final Path root;
    public PostCloseSourcePlan(Path archiveRoot) { root = archiveRoot.toAbsolutePath().normalize(); }

    public Manifest read(RunRequest request) throws IOException {
        if (!"post_close".equals(request.job())) throw new IllegalArgumentException("Post-close plan requested for another job");
        if (!request.rangeStart().equals(request.logicalDate()) || !request.rangeEnd().equals(request.logicalDate()))
            throw new IllegalArgumentException("Post-close source plan only supports one logical trading date");
        if (!request.inputFingerprint().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Post-close plan fingerprint must be a lowercase SHA-256");
        Path path = root.resolve("post-close-plans").resolve(request.inputFingerprint() + ".json");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw new IllegalArgumentException("Frozen post-close core source plan is missing or unsafe");
        Path realRoot = root.toRealPath(); Path realPath = path.toRealPath();
        if (!realPath.startsWith(realRoot)) throw new IllegalArgumentException("Post-close source plan escaped archive root");
        long size = Files.size(realPath);
        if (size < 1 || size > MAX_BYTES) throw new IllegalArgumentException("Post-close source plan size is outside the allowed bound");
        byte[] bytes = Files.readAllBytes(realPath);
        String fingerprint = HexFormat.of().formatHex(sha256(bytes));
        if (!MessageDigest.isEqual(fingerprint.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                request.inputFingerprint().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("Post-close request fingerprint does not match its frozen core source plan");
        if (!fingerprint.equals(request.scopeIdentity()))
            throw new IllegalArgumentException("Post-close request scope identity does not match its frozen core source plan");
        Manifest manifest = Json.MAPPER.readValue(bytes, Manifest.class);
        if (!manifest.logicalDate().equals(request.logicalDate()) || !manifest.calendarVersion().equals(request.calendarVersion())
                || !manifest.zone().equals(request.zone()))
            throw new IllegalArgumentException("Post-close source plan date, calendar, or zone mismatch");
        return manifest;
    }

    public static String fingerprint(byte[] bytes) {
        return HexFormat.of().formatHex(sha256(bytes));
    }
    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
