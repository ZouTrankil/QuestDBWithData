package com.zoutrankil.batch;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.Optional;

/** Same-host process locks; SQLite metadata must remain on a local filesystem. */
final class SqliteOwnershipLock {
    private SqliteOwnershipLock() {}

    static Optional<Handle> tryAcquire(String jdbcUrl,String resource) throws SQLException {
        FileChannel channel=null;
        try {
            Path directory=Path.of(System.getProperty("java.io.tmpdir"),"jdb-sqlite-locks",RunRequest.hash(jdbcUrl));
            Files.createDirectories(directory);
            Path path=directory.resolve(RunRequest.hash(resource)+".lock");
            channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=channel.tryLock();
            if(lock==null) { channel.close(); return Optional.empty(); }
            return Optional.of(new Handle(channel,lock));
        } catch(OverlappingFileLockException busy) {
            close(channel); return Optional.empty();
        } catch(Exception error) {
            close(channel); throw new SQLException("Cannot acquire local SQLite ownership lock",error);
        }
    }

    private static void close(FileChannel channel) {
        if(channel!=null) try { channel.close(); } catch(IOException ignored) { }
    }

    static final class Handle implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        Handle(FileChannel channel,FileLock lock) { this.channel=channel; this.lock=lock; }
        void requireAlive() throws SQLException {
            if(!channel.isOpen() || !lock.isValid()) throw new SQLException("SQLite ownership lock lost");
        }
        @Override public void close() throws SQLException {
            try { lock.release(); channel.close(); }
            catch(IOException error) { throw new SQLException("Cannot release SQLite ownership lock",error); }
        }
    }
}
