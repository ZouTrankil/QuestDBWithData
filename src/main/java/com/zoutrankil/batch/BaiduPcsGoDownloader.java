package com.zoutrankil.batch;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded BaiduPCS-Go adapter. It uses an explicit executable/config directory and no shell. */
public final class BaiduPcsGoDownloader {
    public record StagedArchive(String logicalDate,Path path,long sizeBytes,String accountUid) {}
    private record DownloadIntent(int schemaVersion,String logicalDate,String remotePath,long expectedBytes,String accountUid,String state,String sha256) {}
    public record Config(Path executable,Path configDirectory,Path archiveRoot,String expectedUid,Duration timeout,long maxArchiveBytes) {
        public Config {
            executable=executable.toAbsolutePath().normalize();configDirectory=configDirectory.toAbsolutePath().normalize();archiveRoot=archiveRoot.toAbsolutePath().normalize();
            if(expectedUid==null||!expectedUid.matches("[0-9]{1,24}")||timeout==null||timeout.isNegative()||timeout.isZero()||maxArchiveBytes<1)throw new IllegalArgumentException("BaiduPCS-Go expected UID, positive timeout and archive bound are required");
            if(executable.getParent()==null)throw new IllegalArgumentException("BaiduPCS-Go executable path must be absolute");
        }
    }
    @FunctionalInterface public interface CommandRunner { String run(Path executable,Path workingDirectory,Path configDirectory,List<String> arguments,Duration timeout,boolean captureOutput) throws IOException; }
    private static final Pattern UID=Pattern.compile("(?m)^当前帐号 uid:\\s*([0-9]+)");
    private final Config config;
    private final CommandRunner runner;
    public BaiduPcsGoDownloader(Config config){this(config,BaiduPcsGoDownloader::runProcess);}
    BaiduPcsGoDownloader(Config config,CommandRunner runner){this.config=Objects.requireNonNull(config);this.runner=Objects.requireNonNull(runner);}

    public synchronized StagedArchive download(String remotePath,String logicalDate,long expectedBytes) throws IOException {
        if(!logicalDate.matches("[0-9]{8}")||expectedBytes<1||expectedBytes>config.maxArchiveBytes())throw new IllegalArgumentException("Invalid archive date or size outside the configured bound");
        try { java.time.LocalDate.parse(logicalDate,java.time.format.DateTimeFormatter.BASIC_ISO_DATE); }
        catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid YYYYMMDD logical date");}
        validateRemote(remotePath);
        if(!Files.isRegularFile(config.executable(),LinkOption.NOFOLLOW_LINKS)||!Files.isExecutable(config.executable()))throw new IllegalArgumentException("Configured BaiduPCS-Go executable is unavailable");
        requireSafeDirectory(config.configDirectory());
        String who=runner.run(config.executable(),config.executable().getParent(),config.configDirectory(),List.of("who"),Duration.ofSeconds(30),true);
        Matcher matcher=UID.matcher(who);
        if(!matcher.find()||matcher.group(1).equals("0"))throw new IllegalStateException("BaiduPCS-Go has no verifiable active account");
        String uid=matcher.group(1);
        if(!uid.equals(config.expectedUid()))throw new IllegalStateException("BaiduPCS-Go account UID does not match configured expected UID");

        Path incoming=config.archiveRoot().resolve(".incoming").resolve("baidu");
        requireSafeDirectory(config.archiveRoot());requireSafeDirectory(config.archiveRoot().resolve(".incoming"));requireSafeDirectory(incoming);
        if(Files.getFileStore(incoming).getUsableSpace()<expectedBytes)throw new IllegalStateException("Insufficient local free space for the expected archive size");
        Path prior=findPrior(incoming,remotePath,logicalDate,expectedBytes,uid);
        if(prior!=null)return new StagedArchive(logicalDate,prior,expectedBytes,uid);
        Path attempt=Files.createTempDirectory(incoming,logicalDate+"-");
        Path archive=attempt.resolve(logicalDate+".7z");
        Path manifest=attempt.resolve("download-intent.json");
        writeIntent(manifest,new DownloadIntent(1,logicalDate,remotePath,expectedBytes,uid,"DOWNLOAD_INTENT",""),false);
        runner.run(config.executable(),config.executable().getParent(),config.configDirectory(),
                List.of("download",remotePath,"--saveto",attempt.toString(),"--mode","pcs","--retry","3"),config.timeout(),false);
        if(Files.isSymbolicLink(archive)||!Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("BaiduPCS-Go did not produce the expected regular archive");
        long actual=Files.size(archive);
        if(actual!=expectedBytes)throw new IllegalStateException("Downloaded archive size does not match verified own-drive metadata");
        writeIntent(manifest,new DownloadIntent(1,logicalDate,remotePath,expectedBytes,uid,"DOWNLOADED_UNVALIDATED",sha256(archive)),true);
        return new StagedArchive(logicalDate,archive,actual,uid);
    }

    private static Path findPrior(Path incoming,String remotePath,String date,long expectedBytes,String uid) throws IOException {
        Path recovered=null;
        try(var attempts=Files.list(incoming)) {
            for(Path attempt:attempts.filter(p->p.getFileName().toString().startsWith(date+"-")).toList()) {
                if(Files.isSymbolicLink(attempt)||!Files.isDirectory(attempt,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Baidu download attempt path is unsafe");
                Path manifest=attempt.resolve("download-intent.json");
                if(!Files.exists(manifest,LinkOption.NOFOLLOW_LINKS))continue;
                if(Files.isSymbolicLink(manifest)||!Files.isRegularFile(manifest,LinkOption.NOFOLLOW_LINKS)||Files.size(manifest)>16_384)throw new IllegalStateException("Baidu download intent file is unsafe");
                DownloadIntent intent;
                try{intent=Json.read(Files.readString(manifest,StandardCharsets.UTF_8),DownloadIntent.class);}catch(RuntimeException malformed){throw new IllegalStateException("Baidu download intent is unreadable; manual reconciliation required");}
                if(intent.schemaVersion()!=1||!intent.logicalDate().equals(date)||!intent.remotePath().equals(remotePath)||intent.expectedBytes()!=expectedBytes||!intent.accountUid().equals(uid))
                    throw new IllegalStateException("Baidu download identity changed; manual reconciliation required");
                if(!intent.state().equals("DOWNLOADED_UNVALIDATED"))throw new IllegalStateException("Prior local download outcome is unknown; manual reconciliation required");
                Path archive=attempt.resolve(date+".7z");
                if(Files.isSymbolicLink(archive)||!Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS)||Files.size(archive)!=expectedBytes
                        ||intent.sha256()==null||!intent.sha256().matches("[a-f0-9]{64}")||!intent.sha256().equals(sha256(archive)))
                    throw new IllegalStateException("Prior staged archive does not match its durable download intent");
                if(recovered!=null)throw new IllegalStateException("Multiple completed local downloads exist for this archive; manual reconciliation required");
                recovered=archive;
            }
        }
        return recovered;
    }

    private static void writeIntent(Path path,DownloadIntent intent,boolean replace) throws IOException {
        Path temporary=path.resolveSibling(path.getFileName()+".tmp");
        Files.writeString(temporary,Json.write(intent),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        try {
            if(replace)Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE);
        }
        catch(AtomicMoveNotSupportedException unsupported){Files.deleteIfExists(temporary);throw new IOException("Atomic local download intent update is not supported");}
        catch(IOException error){Files.deleteIfExists(temporary);throw error;}
    }
    private static String sha256(Path file) throws IOException {
        try { var digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[64*1024];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}return java.util.HexFormat.of().formatHex(digest.digest()); }
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }

    private static void validateRemote(String path) {
        if(path==null||!path.startsWith("/")||path.contains("\\")||path.contains("//")||path.chars().anyMatch(Character::isISOControl)
                ||Arrays.stream(path.split("/")).anyMatch(part->part.equals(".")||part.equals(".."))||!path.endsWith(".7z"))
            throw new IllegalArgumentException("Remote download path must be an absolute date-named .7z path");
        String name=path.substring(path.lastIndexOf('/')+1);
        if(!name.matches("[0-9]{8}\\.7z"))throw new IllegalArgumentException("Remote archive basename must be YYYYMMDD.7z");
    }

    private static void requireSafeDirectory(Path path) throws IOException {
        Path normalized=path.toAbsolutePath().normalize();
        // System ancestors such as macOS /var may intentionally be symlinks; reject
        // symlinks at the configured root and each task-owned directory component.
        if(Files.exists(normalized,LinkOption.NOFOLLOW_LINKS)&&Files.isSymbolicLink(normalized))throw new IllegalArgumentException("Baidu download staging path must not contain symlinks");
        Files.createDirectories(normalized);
        if(Files.isSymbolicLink(normalized)||!Files.isDirectory(normalized,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Baidu download staging path is not a safe directory");
    }

    private static String runProcess(Path executable,Path workingDirectory,Path configDirectory,List<String> arguments,Duration timeout,boolean captureOutput) throws IOException {
        var builder=new ProcessBuilder();var command=new ArrayList<String>();command.add(executable.toString());command.addAll(arguments);
        builder.command(command).directory(workingDirectory.toFile()).redirectErrorStream(true);
        Map<String,String> inherited=System.getenv();var environment=builder.environment();environment.clear();
        for(String name:List.of("PATH","SystemRoot","WINDIR","TEMP","TMP","TMPDIR","LANG","LC_ALL"))if(inherited.containsKey(name))environment.put(name,inherited.get(name));
        environment.put("BAIDUPCS_GO_CONFIG_DIR",configDirectory.toString());environment.put("BAIDUPCS_GO_VERBOSE","0");
        if(!captureOutput)builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process=builder.start();
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        Thread reader=null;
        if(captureOutput){reader=Thread.ofVirtual().start(()->{try(InputStream in=process.getInputStream()){byte[] b=new byte[2048];int total=0;for(int n;(n=in.read(b))!=-1;){if(total<16_384){int copy=Math.min(n,16_384-total);output.write(b,0,copy);total+=copy;}}}catch(IOException ignored){}});}
        try {
            if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();throw new IOException("BaiduPCS-Go command timed out");}
            if(reader!=null)reader.join(5_000);
            if(process.exitValue()!=0)throw new IOException("BaiduPCS-Go command failed with exit code "+process.exitValue());
            return captureOutput?output.toString(StandardCharsets.UTF_8):"";
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();throw new IOException("BaiduPCS-Go command interrupted");}
    }
}
