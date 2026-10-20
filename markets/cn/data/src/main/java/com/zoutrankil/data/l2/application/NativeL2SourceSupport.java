package com.zoutrankil.data.l2.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Finite local-file inventory and immutable evidence shared by native L2 source contracts. */
final class NativeL2SourceSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;
    static final long DECODED_PART_ROWS = 1_000_000;

    private NativeL2SourceSupport() {}

    static Path root(Path requested) throws IOException {
        if (!Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(requested))
            throw new IOException("L2 dataset root is missing or linked");
        return requested.toRealPath();
    }

    static Path partition(Path root, String dataset, LocalDate day) {
        return root.resolve(dataset)
                .resolve("year=%04d".formatted(day.getYear()))
                .resolve("month=%02d".formatted(day.getMonthValue()))
                .resolve("trade_date=" + BASIC.format(day));
    }

    static List<Path> parts(Path root, Path directory, boolean contiguous) throws IOException {
        for (Path path = directory; path != null && path.startsWith(root); path = path.getParent())
            if (Files.isSymbolicLink(path))
                throw new IOException("L2 source partition contains a linked path");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("L2 source partition is missing");
        try (var entries = Files.list(directory)) {
            var files = entries.sorted().toList();
            var result = new ArrayList<Path>();
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (contiguous || name.startsWith("part-") && name.endsWith(".parquet")) {
                    if (!name.matches(
                                    contiguous
                                            ? "part-[0-9]+\\.parquet"
                                            : "part-[0-9]{5}\\.parquet")
                            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || Files.isSymbolicLink(file))
                        throw new IOException("L2 source has an unsafe Parquet part");
                    if (contiguous
                            && Integer.parseInt(name.substring(5, name.length() - 8))
                                    != result.size())
                        throw new IOException(
                                "L2 manifest part IDs must start at zero and be contiguous");
                    result.add(file);
                }
            }
            if (result.isEmpty()) throw new IOException("L2 source partition has no Parquet parts");
            return List.copyOf(result);
        }
    }

    static void addFile(
            Path root,
            Path path,
            Map<String, ObjectNode> files,
            int maxFiles,
            long maxBytes,
            NativeL2DailyParquetReader.Control control)
            throws Exception {
        String relative = relative(root, path);
        if (files.containsKey(relative)) return;
        long size = Files.size(path);
        long total =
                files.values().stream().mapToLong(file -> file.path("bytes").longValue()).sum();
        if (size < 1 || files.size() >= maxFiles || size > maxBytes - total)
            throw new IOException("L2 source exceeds its frozen file or byte budget");
        files.put(
                relative,
                JSON.createObjectNode()
                        .put("bytes", size)
                        .put("path", relative)
                        .put("sha256", fileHash(path, control)));
    }

    static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    static String fileHash(Path path, NativeL2DailyParquetReader.Control control) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = new byte[1024 * 1024];
            int count;
            while ((count = input.read(bytes)) != -1) {
                control.run();
                digest.update(bytes, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void verifyFiles(
            Path root, List<ObjectNode> files, NativeL2DailyParquetReader.Control control)
            throws Exception {
        for (var file : files) {
            Path path = root.resolve(text(file, "path"));
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(path)
                    || Files.size(path) != file.path("bytes").longValue()
                    || !text(file, "sha256").equals(fileHash(path, control)))
                throw new IOException("L2 Parquet content changed during native inspection");
        }
    }

    static String text(ObjectNode node, String field) throws IOException {
        var value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank())
            throw new IOException("L2 required text is missing: " + field);
        return value.textValue();
    }

    static int batch(ObjectNode row) throws IOException {
        var node = row.get("batch_id");
        if (node == null
                || !node.isIntegralNumber()
                || !node.canConvertToInt()
                || node.intValue() < 0
                || node.intValue() > 99999) throw new IOException("L2 receipt batch ID is invalid");
        return node.intValue();
    }

    static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static String digest(List<String> parts) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String part : parts) {
            digest.update(part.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void emit(
            BufferedWriter output,
            ObjectNode node,
            long[] bytes,
            long limit,
            NativeL2DailyParquetReader.Control control)
            throws Exception {
        control.run();
        String line = JSON.writeValueAsString(node);
        bytes[0] = Math.addExact(bytes[0], line.getBytes(StandardCharsets.UTF_8).length + 1L);
        if (bytes[0] > limit)
            throw new IOException("L2 stream exceeds its frozen output-byte budget");
        output.write(line);
        output.newLine();
    }

    static final class Deadline extends RuntimeException {
        Deadline(Exception cause) {
            super(cause);
        }
    }

    static boolean check(NativeL2DailyParquetReader.Control control) {
        try {
            control.run();
            return false;
        } catch (Exception failure) {
            throw new Deadline(failure);
        }
    }
}
