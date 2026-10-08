package com.zoutrankil.batch.l2;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;

interface L2CheckpointStore {
    interface RootLock extends AutoCloseable {
        @Override void close() throws IOException;
    }

    interface TextLog extends AutoCloseable {
        void write(String text) throws IOException;
        void newLine() throws IOException;
        void flush() throws IOException;
        @Override void close() throws IOException;
    }

    @FunctionalInterface interface Aggregate {
        void write(TextLog writer) throws IOException;
    }

    void createDirectory(Path directory) throws IOException;
    RootLock lock(Path root) throws IOException;
    TextLog log(Path file) throws IOException;
    void append(Path file, String text) throws IOException;
    void json(Path file, Object value) throws IOException;
    void text(Path file, String value) throws IOException;
    JsonNode checkpoint(Path file) throws IOException;
    boolean readableResult(Path file) throws IOException;
    boolean exists(Path file);
    String hash(Path file) throws IOException;
    JsonNode readJson(Path file) throws IOException;
    String readText(Path file) throws IOException;
    void delete(Path file) throws IOException;
    String aggregate(Path destination, Aggregate action) throws IOException;
}
