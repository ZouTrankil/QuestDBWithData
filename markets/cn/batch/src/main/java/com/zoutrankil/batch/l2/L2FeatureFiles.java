package com.zoutrankil.batch.l2;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

final class L2FeatureFiles {
    private static final ObjectMapper JSON = new ObjectMapper();
    private L2FeatureFiles() {
    }

    static void atomicJson(Path target, Object value) throws IOException {
        atomicText(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    static void atomicText(Path target, String text) throws IOException {
        Path temp = temporary(target);
        try {
            try (FileChannel channel = FileChannel.open(temp, CREATE_NEW, WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally {
            Files.deleteIfExists(temp);
        }
    }

    static Path temporary(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
    }

    static void force(Path path) throws IOException {
        try (var channel = FileChannel.open(path, WRITE)) {
            channel.force(true);
        }
    }

    static String sha256(Path file) throws IOException {
        var digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            transferDigest(input, digest);
        }
        return hex(digest);
    }

    static void transferDigest(InputStream input, MessageDigest digest) throws IOException {
        byte[] bytes = new byte[65536];
        for (int count; (count = input.read(bytes)) != -1;) digest.update(bytes, 0, count);
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static void update(MessageDigest digest, String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
    }

    static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }
}
