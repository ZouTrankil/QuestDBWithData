package com.zoutrankil.batch.l2;

import java.io.IOException;
import java.util.concurrent.CancellationException;

/**
 * 计算任务与取消操作共用的发布闸门。
 * 发布与关闭串行执行；关闭后拒绝新的发布，已进入的发布动作先完成。
 */
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
