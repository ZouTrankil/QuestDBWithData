package com.zoutrankil.batch.l2;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class L2BatchExecutionSession {
    final ExecutorService pool;
    final L2PublicationGate publication = new L2PublicationGate();
    private InterruptedException interrupted;
    L2BatchExecutionSession(int workers) {
        pool = Executors.newFixedThreadPool(workers);
    }

    void interrupted(InterruptedException failure) {
        if (interrupted == null) interrupted = failure;
        else if (interrupted != failure) interrupted.addSuppressed(failure);
    }

    void stop() {
        publication.close();
        pool.shutdownNow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return;
            try {
                pool.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                return;
            }
            catch (InterruptedException failure) {
                interrupted(failure);
            }
        }
    }

    InterruptedException interruption() {
        return interrupted;
    }

    void propagateInterrupted() throws InterruptedException {
        if (interrupted != null) throw interrupted;
    }

    Exception combine(Exception failure) {
        if (interrupted == null || failure == interrupted) return failure;
        interrupted.addSuppressed(failure);
        return interrupted;
    }

    void attachInterrupted(Error failure) {
        if (interrupted != null) failure.addSuppressed(interrupted);
    }

    void restoreInterrupted() {
        if (interrupted != null) Thread.currentThread().interrupt();
    }
}
