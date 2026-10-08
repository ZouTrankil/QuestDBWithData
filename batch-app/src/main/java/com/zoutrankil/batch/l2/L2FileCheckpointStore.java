package com.zoutrankil.batch.l2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import static com.zoutrankil.batch.l2.L2FeatureFiles.atomicJson;
import static com.zoutrankil.batch.l2.L2FeatureFiles.atomicText;
import static com.zoutrankil.batch.l2.L2FeatureFiles.force;
import static com.zoutrankil.batch.l2.L2FeatureFiles.sha256;
import static com.zoutrankil.batch.l2.L2FeatureFiles.temporary;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

final class L2FileCheckpointStore implements L2CheckpointStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CHECKPOINT_BYTES = 1048576;
    @Override public void createDirectory(Path path) throws IOException {
        Files.createDirectories(path);
    }

    @Override public RootLock lock(Path root) throws IOException {
        var channel = FileChannel.open(root.resolve("batch.lock"), CREATE, WRITE);
        try {
            var lock = channel.tryLock();
            if (lock == null) throw new IOException("Another batch owns output-root: " + root);
            return () -> {
                try (channel; lock) {
                }
            };
        }
        catch (IOException|RuntimeException|Error failure) {
            try {
                channel.close();
            }
            catch (IOException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    @Override public TextLog log(Path path) throws IOException {
        return new Log(Files.newBufferedWriter(path, StandardCharsets.UTF_8, CREATE, TRUNCATE_EXISTING, WRITE));
    }

    private record Log(BufferedWriter writer) implements TextLog {
        @Override public void write(String value) throws IOException {
            writer.write(value);
        }
        @Override public void newLine() throws IOException {
            writer.newLine();
        }
        @Override public void flush() throws IOException {
            writer.flush();
        }
        @Override public void close() throws IOException {
            writer.close();
        }
    }

    @Override public void append(Path path, String text) throws IOException {
        Files.writeString(path, text, StandardCharsets.UTF_8, CREATE, APPEND);
    }

    @Override public void json(Path path, Object value) throws IOException {
        atomicJson(path, value);
    }

    @Override public void text(Path path, String value) throws IOException {
        atomicText(path, value);
    }

    @Override public JsonNode checkpoint(Path path) throws IOException {
        return !Files.isRegularFile(path) || Files.size(path)>MAX_CHECKPOINT_BYTES?null:JSON.readTree(path.toFile());
    }

    @Override public boolean readableResult(Path path) throws IOException {
        return Files.isRegularFile(path) && Files.size(path) <= MAX_CHECKPOINT_BYTES;
    }

    @Override public boolean exists(Path path) {
        return Files.exists(path);
    }

    @Override public String hash(Path path) throws IOException {
        return sha256(path);
    }

    @Override public JsonNode readJson(Path path) throws IOException {
        return JSON.readTree(path.toFile());
    }

    @Override public String readText(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Override public void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    @Override public String aggregate(Path destination, Aggregate action) throws IOException {
        Path temp = temporary(destination);
        try {
            try (var writer = new Log(Files.newBufferedWriter(temp, StandardCharsets.UTF_8, CREATE_NEW, WRITE))) {
                action.write(writer);
            }
            force(temp);
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return sha256(destination);
        }
        finally {
            Files.deleteIfExists(temp);
        }
    }
}
