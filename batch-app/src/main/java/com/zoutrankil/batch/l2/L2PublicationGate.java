package com.zoutrankil.batch.l2;

import java.io.IOException;
import java.util.concurrent.CancellationException;

final class L2PublicationGate {
    @FunctionalInterface interface Publication {
        void run() throws IOException;
    }

    private boolean open = true;
    synchronized void publish(Publication action) throws IOException {
        if (!open || Thread.currentThread().isInterrupted()) throw new CancellationException("L2 batch publication authority closed");
        action.run();
    }

    synchronized void close() {
        open = false;
    }
}
