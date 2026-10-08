package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BaiduPcsGoDownloaderTest {
    @TempDir Path temp;
    private BaiduPcsGoDownloader downloader(BaiduPcsGoDownloader.CommandRunner runner) throws IOException {
        Path executable=temp.resolve("BaiduPCS-Go");Files.writeString(executable,"fixture");executable.toFile().setExecutable(true);
        return new BaiduPcsGoDownloader(new BaiduPcsGoDownloader.Config(executable,temp.resolve("private-cli-config"),temp.resolve("archives"),"3686470622",Duration.ofMinutes(1),1024),runner);
    }

    @Test void downloadsToUniqueIsolatedStagingAndChecksConfiguredAccountAndExactSize() throws Exception {
        var calls=new ArrayList<List<String>>();byte[] bytes={1,2,3,4};
        var downloader=downloader((exe,cwd,config,args,timeout,capture)->{
            calls.add(args);if(args.getFirst().equals("who"))return "当前帐号 uid: 3686470622";
            assertFalse(args.contains("--ow"));Path destination=Path.of(args.get(args.indexOf("--saveto")+1));Files.write(destination.resolve("20260930.7z"),bytes);return "";
        });
        var result=downloader.download("/L2/202609/20260930.7z","20260930",bytes.length);
        assertEquals(4,result.sizeBytes());assertTrue(result.path().startsWith(temp.resolve("archives/.incoming/baidu")));
        assertEquals("3686470622",result.accountUid());assertEquals("who",calls.getFirst().getFirst());assertEquals("download",calls.getLast().getFirst());
    }

    @Test void accountMismatchStopsBeforeDownloadAndDoesNotExposeWhoOutput() throws Exception {
        var calls=new ArrayList<List<String>>();var downloader=downloader((exe,cwd,config,args,timeout,capture)->{calls.add(args);return "当前帐号 uid: 9999999999 private-account-name";});
        var error=assertThrows(IllegalStateException.class,()->downloader.download("/L2/202609/20260930.7z","20260930",4));
        assertEquals("BaiduPCS-Go account UID does not match configured expected UID",error.getMessage());assertEquals(1,calls.size());
    }

    @Test void wrongSizeIsRetainedOnlyAsUntrustedPartialAndBadRemotePathsAreRejected() throws Exception {
        var downloader=downloader((exe,cwd,config,args,timeout,capture)->{
            if(args.getFirst().equals("who"))return "当前帐号 uid: 3686470622";
            Path destination=Path.of(args.get(args.indexOf("--saveto")+1));Files.write(destination.resolve("20260930.7z"),new byte[]{1});return "";
        });
        assertThrows(IllegalStateException.class,()->downloader.download("/L2/202609/20260930.7z","20260930",4));
        assertThrows(IllegalArgumentException.class,()->downloader.download("/L2/../20260930.7z","20260930",4));
        assertThrows(IllegalArgumentException.class,()->downloader.download("/L2/not-a-date.7z","20260930",4));
        assertThrows(IllegalArgumentException.class,()->downloader.download("/L2/202609/20260930.7z","20260930",1025));
    }

    @Test void refusesSymlinkedStagingRoot() throws Exception {
        Path real=temp.resolve("real");Files.createDirectories(real);Path alias=temp.resolve("alias");supportedSymlink(alias,real);
        Path executable=temp.resolve("pcs");Files.writeString(executable,"fixture");executable.toFile().setExecutable(true);
        var downloader=new BaiduPcsGoDownloader(new BaiduPcsGoDownloader.Config(executable,temp.resolve("cfg"),alias,"3686470622",Duration.ofSeconds(1),1024),
                (exe,cwd,config,args,timeout,capture)->"当前帐号 uid: 3686470622");
        assertThrows(IllegalArgumentException.class,()->downloader.download("/20260930.7z","20260930",1));
    }

    @Test void completedStagingIsReusedAfterRestartWithoutDownloadingAgain() throws Exception {
        byte[] bytes={8,7,6};var downloadCount=new java.util.concurrent.atomic.AtomicInteger();
        var first=downloader((exe,cwd,config,args,timeout,capture)->{
            if(args.getFirst().equals("who"))return "当前帐号 uid: 3686470622";
            downloadCount.incrementAndGet();Path folder=Path.of(args.get(args.indexOf("--saveto")+1));Files.write(folder.resolve("20260930.7z"),bytes);return "";
        });
        var original=first.download("/L2/202609/20260930.7z","20260930",bytes.length);
        var restarted=downloader((exe,cwd,config,args,timeout,capture)->{if(args.getFirst().equals("who"))return "当前帐号 uid: 3686470622";throw new AssertionError("download must not be repeated");});
        var recovered=restarted.download("/L2/202609/20260930.7z","20260930",bytes.length);
        assertEquals(original.path(),recovered.path());assertEquals(1,downloadCount.get());
    }

    @Test void unknownDownloadIntentBlocksASecondProcessAfterRestart() throws Exception {
        var first=downloader((exe,cwd,config,args,timeout,capture)->{
            if(args.getFirst().equals("who"))return "当前帐号 uid: 3686470622";
            throw new IOException("simulated lost process outcome");
        });
        assertThrows(IOException.class,()->first.download("/L2/202609/20260930.7z","20260930",3));
        var restarted=downloader((exe,cwd,config,args,timeout,capture)->{if(args.getFirst().equals("who"))return "当前帐号 uid: 3686470622";throw new AssertionError("unknown download must not be restarted");});
        var error=assertThrows(IllegalStateException.class,()->restarted.download("/L2/202609/20260930.7z","20260930",3));
        assertTrue(error.getMessage().contains("outcome is unknown"));
    }

    @Test void sameSizeStagingMutationIsDetectedByPersistedFingerprint() throws Exception {
        byte[] bytes={1,2,3};var first=downloader((exe,cwd,config,args,timeout,capture)->{
            if(args.getFirst().equals("who"))return "当前帐号 uid: 3686470622";
            Path folder=Path.of(args.get(args.indexOf("--saveto")+1));Files.write(folder.resolve("20260930.7z"),bytes);return "";
        });
        var original=first.download("/L2/202609/20260930.7z","20260930",bytes.length);Files.write(original.path(),new byte[]{3,2,1});
        var restarted=downloader((exe,cwd,config,args,timeout,capture)->{if(args.getFirst().equals("who"))return "当前帐号 uid: 3686470622";throw new AssertionError("mutated staging must not be redownloaded automatically");});
        assertThrows(IllegalStateException.class,()->restarted.download("/L2/202609/20260930.7z","20260930",bytes.length));
    }

    private static Path supportedSymlink(Path link, Path target) throws java.io.IOException {
        try { return Files.createSymbolicLink(link, target); }
        catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Actual temporary-directory symlink capability unavailable: " + unavailable);
            throw new AssertionError("Assumption must abort only this capability test", unavailable);
        }
    }

}
