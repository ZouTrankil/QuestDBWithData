package com.zoutrankil.data.repository;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Byte-oriented evidence files; callers own formats, size policies and collision decisions. */
public final class FileEvidenceStore {
    public enum Ownership { DIRECT_CHILD, DESCENDANT }
    public enum Symlinks { ALLOW_WITHIN_ROOT, REJECT_LEAF }

    private FileEvidenceStore() {}

    public static <E extends Exception> byte[] readBounded(Path path,int maximum,Supplier<E> exceeded) throws IOException,E {
        if(maximum<1||maximum==Integer.MAX_VALUE)
            throw new IllegalArgumentException("Evidence byte maximum must be between 1 and 2147483646");
        Objects.requireNonNull(exceeded);
        try(var input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes(maximum+1);
            if(bytes.length>maximum)throw exceeded.get();
            return bytes;
        }
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static Path writeNew(Path path,byte[] bytes) throws IOException {
        return Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
    }

    public static Path writeNewUtf8(Path path,CharSequence text) throws IOException {
        var encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
        byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);
        return writeNew(path,bytes);
    }

    public static void writeNewDurable(Path path,byte[] bytes) throws IOException {
        try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            var buffer=ByteBuffer.wrap(bytes);
            while(buffer.hasRemaining())channel.write(buffer);
            channel.force(true);
        }
    }

    public static void replaceDurable(Path path,byte[] bytes) throws IOException {
        Path temporary=path.resolveSibling(path.getFileName()+".tmp-"+UUID.randomUUID());
        Throwable failure=null;
        try {
            writeNewDurable(temporary,bytes);
            Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException|RuntimeException|Error original) {
            failure=original;
            throw original;
        } finally {
            if(failure==null)Files.deleteIfExists(temporary);
            else try { Files.deleteIfExists(temporary); }
            catch(IOException|RuntimeException cleanup) { failure.addSuppressed(cleanup); }
        }
    }

    /** Checks lexical and real ownership of an existing path without changing the returned path to its link target. */
    public static <E extends Exception> Path owned(Path path,Path root,Ownership ownership,Symlinks symlinks,
                                                  Supplier<E> rejected) throws IOException,E {
        Objects.requireNonNull(ownership);Objects.requireNonNull(symlinks);Objects.requireNonNull(rejected);
        Path normalized=path.toAbsolutePath().normalize(),normalizedRoot=root.toAbsolutePath().normalize();
        if(!belongs(normalized,normalizedRoot,ownership))throw rejected.get();
        if(symlinks==Symlinks.REJECT_LEAF&&Files.isSymbolicLink(normalized))throw rejected.get();
        Path real=normalized.toRealPath(),realRoot=normalizedRoot.toRealPath();
        if(!belongs(real,realRoot,ownership))throw rejected.get();
        return normalized;
    }

    private static boolean belongs(Path path,Path root,Ownership ownership) {
        return ownership==Ownership.DIRECT_CHILD ? root.equals(path.getParent())
                : !path.equals(root)&&path.startsWith(root);
    }
}
