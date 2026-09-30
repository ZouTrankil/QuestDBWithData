package com.zoutrankil.batch;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Objects;

/** Explicitly composes cloud transfer and local .incoming download; admission remains a separate Batch gate. */
public final class BaiduL2ArchiveDownloader {
    public record Result(String status,String logicalDate,String remotePath,String localArchive,long sizeBytes) {}
    private final BaiduL2Subscription subscription;
    private final BaiduPcsGoDownloader downloader;
    public BaiduL2ArchiveDownloader(BaiduL2Subscription subscription,BaiduPcsGoDownloader downloader){this.subscription=Objects.requireNonNull(subscription);this.downloader=Objects.requireNonNull(downloader);}
    public Result download(LocalDate date,boolean dryRun) throws IOException {
        var transfer=subscription.transfer(date,dryRun);
        if(dryRun)return new Result(transfer.status(),transfer.logicalDate(),transfer.remotePath(),"",transfer.expectedBytes());
        if(!transfer.status().equals("SAVED")&&!transfer.status().equals("PRESENT"))return new Result(transfer.status(),transfer.logicalDate(),transfer.remotePath(),"",transfer.expectedBytes());
        var staged=downloader.download(transfer.remotePath(),transfer.logicalDate(),transfer.expectedBytes());
        return new Result("DOWNLOADED_UNVALIDATED",staged.logicalDate(),transfer.remotePath(),staged.path().toString(),staged.sizeBytes());
    }
}
