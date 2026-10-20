package com.zoutrankil.data.marketdata;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/** 日期锁负责数据冲突；这个短期进程锁额外串行化同一 SQLite 账本的整次发布。 */
public final class MarketPublisherLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private MarketPublisherLease(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static MarketPublisherLease acquire(Path path, Duration timeout)
            throws IOException, InterruptedException {
        if (timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(15)) > 0)
            throw new IllegalArgumentException("发布锁等待须有界且不超过十五分钟");
        Files.createDirectories(path.toAbsolutePath().getParent());
        var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            do {
                try {
                    var lock = channel.tryLock();
                    if (lock != null) return new MarketPublisherLease(channel, lock);
                } catch (OverlappingFileLockException held) {
                    /* 同一 JVM 也遵守同一进程锁。 */
                }
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            throw new IOException("另一发布仍持有共享账本，尚未取得发送资格");
        } catch (IOException | InterruptedException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
