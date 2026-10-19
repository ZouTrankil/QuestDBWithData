package com.zoutrankil.data.marketdata;

import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;

/** Windows 的短暂读取可能阻止替换；状态页仅供观察，不能让它覆盖 SQLite 的真实终态。 */
public final class AtomicMarketStatus {
    private AtomicMarketStatus() {}
    public static boolean write(Path target,byte[] bytes)throws IOException,InterruptedException {
        Path pending=target.resolveSibling(target.getFileName()+"."+UUID.randomUUID()+".pending");Files.write(pending,bytes,StandardOpenOption.CREATE_NEW);
        try {
            for(int attempt=0;attempt<30;attempt++) {
                try {Files.move(pending,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);return true;}
                catch(AccessDeniedException busy){Thread.sleep(50);}
            }return false;
        } finally {try{Files.deleteIfExists(pending);}catch(AccessDeniedException busy){ /* 下次可清理的观察临时文件，不作为检查点。 */ }}
    }
}
