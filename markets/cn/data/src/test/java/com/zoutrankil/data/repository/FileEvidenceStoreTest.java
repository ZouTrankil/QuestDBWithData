package com.zoutrankil.data.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zoutrankil.data.repository.FileEvidenceStore.Ownership.*;
import static com.zoutrankil.data.repository.FileEvidenceStore.Symlinks.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class FileEvidenceStoreTest {
    @TempDir Path temporary;

    @Test void boundedReadsAcceptEmptyAndExactLengthAndRejectTheExtraByte() throws Exception {
        var path=temporary.resolve("bounded.bin");var failures=new AtomicInteger();
        var exceeded=new IOException("caller byte limit");
        java.util.function.Supplier<IOException> error=()->{failures.incrementAndGet();return exceeded;};
        Files.write(path,new byte[0]);
        assertArrayEquals(new byte[0],FileEvidenceStore.readBounded(path,4,error));
        var exact=new byte[]{1,2,3,4};Files.write(path,exact);
        assertArrayEquals(exact,FileEvidenceStore.readBounded(path,4,error));
        assertEquals(0,failures.get());
        Files.write(path,new byte[]{1,2,3,4,5});
        assertSame(exceeded,assertThrowsExactly(IOException.class,()->FileEvidenceStore.readBounded(path,4,error)));
        assertEquals(1,failures.get());
    }

    @Test void boundsAreValidatedBeforeOpeningFilesAndCannotOverflowTheExtraByte() throws Exception {
        var missing=temporary.resolve("missing.bin");
        for(int maximum:new int[]{-1,0,Integer.MAX_VALUE})
            assertThrowsExactly(IllegalArgumentException.class,()->FileEvidenceStore.readBounded(missing,maximum,
                    ()->new IOException("must not be requested")));
        var tiny=Files.write(temporary.resolve("tiny.bin"),new byte[]{7});
        assertArrayEquals(new byte[]{7},FileEvidenceStore.readBounded(tiny,Integer.MAX_VALUE-1,
                ()->new IOException("too large")));
        assertThrows(IOException.class,()->FileEvidenceStore.readBounded(missing,1,()->new IOException("too large")));
        assertFalse(Files.exists(missing));
    }

    @Test void digestUsesRawBytesAndLowercaseSha256() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                FileEvidenceStore.sha256(new byte[0]));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                FileEvidenceStore.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void immutableWritesKeepUtf8BytesAndNeverReplaceAnExistingFile() throws Exception {
        var path=temporary.resolve("immutable.txt");String value="证据\n😀";
        assertEquals(path,FileEvidenceStore.writeNewUtf8(path,new StringBuilder(value)));
        var original=value.getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(original,Files.readAllBytes(path));
        assertThrows(FileAlreadyExistsException.class,()->FileEvidenceStore.writeNew(path,new byte[]{1}));
        assertThrows(FileAlreadyExistsException.class,()->FileEvidenceStore.writeNewUtf8(path,"replacement"));
        assertThrows(FileAlreadyExistsException.class,()->FileEvidenceStore.writeNewDurable(path,new byte[]{2}));
        assertArrayEquals(original,Files.readAllBytes(path));
    }

    @Test void durableWritesPersistTheCompleteBufferWithoutCreatingParentDirectories() throws Exception {
        var bytes=new byte[128*1024+1];
        for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;
        var path=temporary.resolve("durable.bin");
        FileEvidenceStore.writeNewDurable(path,bytes);
        assertArrayEquals(bytes,Files.readAllBytes(path));
        var missingParent=temporary.resolve("missing/child.bin");
        assertThrows(IOException.class,()->FileEvidenceStore.writeNewDurable(missingParent,bytes));
        assertFalse(Files.exists(missingParent.getParent()));
    }

    @Test void malformedUtf16IsRejectedBeforeAnImmutableUtf8FileIsCreated() {
        for(char unpaired:new char[]{(char)0xd800,(char)0xdc00}){
            var path=temporary.resolve("malformed-"+(int)unpaired+".txt");
            assertThrows(java.nio.charset.CharacterCodingException.class,
                    ()->FileEvidenceStore.writeNewUtf8(path,"prefix"+unpaired+"suffix"));
            assertFalse(Files.exists(path));
        }
    }

    @Test void atomicReplacementReplacesContentAndLeavesNoTemporaryFile() throws Exception {
        var path=FileEvidenceStore.writeNew(temporary.resolve("replace.bin"),new byte[]{1});
        try { FileEvidenceStore.replaceDurable(path,new byte[]{2,3,4}); }
        catch(AtomicMoveNotSupportedException unsupported) {
            assertArrayEquals(new byte[]{1},Files.readAllBytes(path));
            assertNoTemporaryFiles();
            assumeTrue(false,"Atomic move capability unavailable: "+unsupported);
        }
        assertArrayEquals(new byte[]{2,3,4},Files.readAllBytes(path));
        assertNoTemporaryFiles();
    }

    @Test void failedReplacementCleansItsTemporaryFileAndKeepsTheDestination() throws Exception {
        var directory=Files.createDirectory(temporary.resolve("destination"));
        var child=Files.writeString(directory.resolve("keep.txt"),"keep");
        assertThrows(IOException.class,()->FileEvidenceStore.replaceDurable(directory,new byte[]{9}));
        assertEquals("keep",Files.readString(child));
        assertNoTemporaryFiles();
    }

    @Test void partialDurableWriteFailureKeepsDestinationAndCleansTemporaryFile() throws Exception {
        Path destination=Files.write(temporary.resolve("original.bin"),new byte[]{1,2});
        var failure=new IOException("injected partial write or force failure");
        try(var store=mockStatic(FileEvidenceStore.class,CALLS_REAL_METHODS)) {
            store.when(()->FileEvidenceStore.writeNewDurable(any(Path.class),any(byte[].class)))
                    .thenAnswer(call->{Files.write(call.getArgument(0,Path.class),new byte[]{9});throw failure;});
            assertSame(failure,assertThrows(IOException.class,
                    ()->FileEvidenceStore.replaceDurable(destination,new byte[]{9,8,7})));
        }
        assertArrayEquals(new byte[]{1,2},Files.readAllBytes(destination));
        assertNoTemporaryFiles();
    }

    @Test void failedCleanupIsSuppressedOnTheOriginalDurableWriteFailure() throws Exception {
        Path destination=Files.write(temporary.resolve("original.bin"),new byte[]{1,2});
        var failure=new IOException("injected durable write failure");
        try(var store=mockStatic(FileEvidenceStore.class,CALLS_REAL_METHODS)) {
            store.when(()->FileEvidenceStore.writeNewDurable(any(Path.class),any(byte[].class)))
                    .thenAnswer(call->{
                        Path temporaryFile=Files.createDirectory(call.getArgument(0,Path.class));
                        Files.write(temporaryFile.resolve("retained"),new byte[]{9});
                        throw failure;
                    });
            assertSame(failure,assertThrows(IOException.class,
                    ()->FileEvidenceStore.replaceDurable(destination,new byte[]{9,8,7})));
        }
        assertArrayEquals(new byte[]{1,2},Files.readAllBytes(destination));
        assertEquals(1,failure.getSuppressed().length);
        assertInstanceOf(java.nio.file.DirectoryNotEmptyException.class,failure.getSuppressed()[0]);
    }

    @Test void ownershipDistinguishesDirectChildrenAndDescendantsAndRejectsLexicalEscape() throws Exception {
        var root=Files.createDirectory(temporary.resolve("owner"));
        var child=Files.writeString(root.resolve("child.txt"),"child");
        var nested=Files.createDirectories(root.resolve("nested")).resolve("nested.txt");Files.writeString(nested,"nested");
        var rejected=new IllegalArgumentException("caller ownership failure");
        assertEquals(child.toAbsolutePath(),FileEvidenceStore.owned(root.resolve("nested/../child.txt"),root,
                DIRECT_CHILD,ALLOW_WITHIN_ROOT,()->rejected));
        assertEquals(nested.toAbsolutePath(),FileEvidenceStore.owned(nested,root,DESCENDANT,REJECT_LEAF,()->rejected));
        for(var path:new Path[]{nested,root,temporary.resolve("outside.txt"),root.resolve("../outside.txt")})
            assertSame(rejected,assertThrowsExactly(IllegalArgumentException.class,
                    ()->FileEvidenceStore.owned(path,root,DIRECT_CHILD,ALLOW_WITHIN_ROOT,()->rejected)));
        assertSame(rejected,assertThrowsExactly(IllegalArgumentException.class,
                ()->FileEvidenceStore.owned(root,root,DESCENDANT,ALLOW_WITHIN_ROOT,()->rejected)));
        assertThrows(IOException.class,()->FileEvidenceStore.owned(root.resolve("absent.txt"),root,
                DIRECT_CHILD,ALLOW_WITHIN_ROOT,()->rejected));
    }

    @Test void leafSymlinkPolicyAllowsAnOwnedTargetOrRejectsTheLinkExplicitly() throws Exception {
        var root=Files.createDirectory(temporary.resolve("owner"));
        var target=Files.writeString(root.resolve("target.txt"),"owned");
        var link=createSymbolicLink(root.resolve("link.txt"),target);
        var rejected=new IOException("leaf link rejected");
        assertEquals(link,FileEvidenceStore.owned(link,root,DIRECT_CHILD,ALLOW_WITHIN_ROOT,()->rejected));
        assertSame(rejected,assertThrowsExactly(IOException.class,
                ()->FileEvidenceStore.owned(link,root,DIRECT_CHILD,REJECT_LEAF,()->rejected)));
        var alias=createSymbolicLink(temporary.resolve("owner-alias"),root);
        assertEquals(alias.resolve("target.txt"),FileEvidenceStore.owned(alias.resolve("target.txt"),alias,
                DIRECT_CHILD,REJECT_LEAF,()->rejected));
    }

    @Test void realPathChecksRejectEscapingLeafAndAncestorSymlinks() throws Exception {
        var root=Files.createDirectory(temporary.resolve("owner"));
        var outside=Files.createDirectory(temporary.resolve("outside"));
        var target=Files.writeString(outside.resolve("target.txt"),"outside");
        var leaf=createSymbolicLink(root.resolve("leaf.txt"),target);
        var ancestor=createSymbolicLink(root.resolve("redirect"),outside);
        var rejected=new IllegalStateException("outside real owner");
        assertSame(rejected,assertThrowsExactly(IllegalStateException.class,
                ()->FileEvidenceStore.owned(leaf,root,DIRECT_CHILD,ALLOW_WITHIN_ROOT,()->rejected)));
        assertSame(rejected,assertThrowsExactly(IllegalStateException.class,
                ()->FileEvidenceStore.owned(ancestor.resolve("target.txt"),root,DESCENDANT,REJECT_LEAF,()->rejected)));
    }

    private void assertNoTemporaryFiles() throws IOException {
        try(var paths=Files.list(temporary)) {
            assertTrue(paths.noneMatch(path->path.getFileName().toString().contains(".tmp-")));
        }
    }

    private static Path createSymbolicLink(Path link,Path target) throws IOException {
        try { return Files.createSymbolicLink(link,target); }
        catch(IOException|UnsupportedOperationException|SecurityException unavailable) {
            assumeTrue(false,"Symbolic link capability unavailable: "+unavailable);
            throw new AssertionError("Assumption must abort the test",unavailable);
        }
    }
}
