package com.zoutrankil.batch;

import java.time.LocalDate;
import java.util.*;

/** Plans or performs one bounded archive share-to-own-drive transfer. Local download is a separate step. */
public final class BaiduL2Subscription {
    public record Settings(String shareUrl,String sharePassword,String sourcePath,boolean unwrapSingleFolder,
                           String remoteRoot,long minimumSizeBytes) {
        public Settings {
            if(shareUrl==null||sourcePath==null||remoteRoot==null||minimumSizeBytes<1)throw new IllegalArgumentException("Incomplete Baidu subscription settings");
            if(!remoteRoot.startsWith("/")||remoteRoot.contains("\\")||remoteRoot.contains("//")||hasControl(remoteRoot)||Arrays.asList(remoteRoot.split("/")).stream().anyMatch(part->part.equals(".")||part.equals("..")))throw new IllegalArgumentException("Baidu destination must be a safe absolute own-drive path");
            if(sourcePath.contains("\\")||hasControl(sourcePath))throw new IllegalArgumentException("Baidu source path contains invalid characters");
            for(String component:sourcePath.split("/"))if(component.equals(".")||component.equals(".."))throw new IllegalArgumentException("Baidu source path escapes the share");
        }
    }
    public record Result(String status,String logicalDate,String remotePath,long expectedBytes) {}
    private final BaiduShareClient client;
    private final Settings settings;
    private final BaiduTransferIntentStore intents;
    public BaiduL2Subscription(BaiduShareClient client,Settings settings,BaiduTransferIntentStore intents){this.client=Objects.requireNonNull(client);this.settings=Objects.requireNonNull(settings);this.intents=Objects.requireNonNull(intents);}

    public synchronized Result transfer(LocalDate date,boolean dryRun) {
        Objects.requireNonNull(date);String ymd=date.toString().replace("-","");String filename=ymd+".7z";
        String remoteRoot=settings.remoteRoot().replaceAll("/+$", "");
        String remote=remoteRoot+"/"+ymd.substring(0,4)+"/"+ymd.substring(4,6)+"/"+filename;
        List<BaiduShareClient.Entry> existing=client.metadata(remote);
        var pending=intents.get(ymd);
        if(pending.isPresent()) {
            var intent=pending.get();
            if(!intent.remotePath().equals(remote))throw new IllegalStateException("Persisted Baidu transfer target changed; manual reconciliation required");
            if(existing.size()==1&&!existing.getFirst().directory()&&existing.getFirst().size()==intent.sourceSizeBytes()) {
                intents.verified(ymd);return new Result("PRESENT",ymd,remote,intent.sourceSizeBytes());
            }
            throw new IllegalStateException("Prior Baidu transfer outcome is unknown; destination does not prove the expected archive, manual reconciliation required");
        }
        if(!existing.isEmpty()) {
            if(existing.size()!=1||existing.getFirst().directory()||existing.getFirst().size()<settings.minimumSizeBytes())throw new IllegalStateException("Existing own-drive archive is missing or undersized");
            return new Result("PRESENT",ymd,remote,existing.getFirst().size());
        }
        var share=BaiduShareClient.normalizeShare(settings.shareUrl(),settings.sharePassword());
        if(!share.password().isBlank())client.accessShared(share);
        List<BaiduShareClient.Entry> entries=client.sharedRoot(share);
        if(settings.unwrapSingleFolder()&&entries.size()==1&&entries.getFirst().directory())entries=client.allSharedChildren(entries.getFirst());
        String relative=settings.sourcePath().replace("{year}",ymd.substring(0,4)).replace("{month}",ymd.substring(4,6)).replace("{yyyymm}",ymd.substring(0,6)).replace("{date}",ymd);
        for(String component:relative.split("/")) {
            if(component.isBlank())continue;
            List<BaiduShareClient.Entry> match=entries.stream().filter(e->e.directory()&&basename(e.path()).equals(component)).toList();
            if(match.isEmpty())return new Result("WAITING",ymd,"",0);
            if(match.size()!=1)throw new IllegalStateException("Ambiguous Baidu subscription directory");
            entries=client.allSharedChildren(match.getFirst());
        }
        List<BaiduShareClient.Entry> candidates=entries.stream().filter(e->!e.directory()&&basename(e.path()).equals(filename)).toList();
        if(candidates.isEmpty())return new Result("WAITING",ymd,"",0);
        if(candidates.size()!=1)throw new IllegalStateException("Duplicate Baidu archive names in subscription");
        var source=candidates.getFirst();
        if(source.size()<settings.minimumSizeBytes())throw new IllegalStateException("Subscription archive is undersized; publication may be incomplete");
        if(dryRun)return new Result("PLANNED",ymd,remote,source.size());
        var intent=intents.reserve(ymd,remote,source.fsId(),source.size());
        if(intent.state()!=BaiduTransferIntentStore.State.INTENT)throw new IllegalStateException("Prior Baidu transfer intent requires reconciliation");
        intents.unknown(ymd);
        client.ensureDirectory(remote.substring(0,remote.lastIndexOf('/')));
        client.transfer(remote.substring(0,remote.lastIndexOf('/')),List.of(source.fsId()),source.uk(),source.shareId(),source.token(),share);
        List<BaiduShareClient.Entry> transferred=client.metadata(remote);
        if(transferred.size()!=1||transferred.getFirst().directory()||transferred.getFirst().size()!=source.size())throw new IllegalStateException("Transferred archive is not visible at the expected size");
        intents.verified(ymd);
        return new Result("SAVED",ymd,remote,source.size());
    }
    private static String basename(String path){int index=path.lastIndexOf('/');return path.substring(index+1);}
    private static boolean hasControl(String value){return value.chars().anyMatch(Character::isISOControl);}
}
