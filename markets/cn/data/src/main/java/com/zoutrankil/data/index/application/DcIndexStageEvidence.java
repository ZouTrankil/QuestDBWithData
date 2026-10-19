package com.zoutrankil.data.index.application;
import java.nio.file.*;
/** Run-local stage evidence inventory used by application recovery and lease retention. */
final class DcIndexStageEvidence {
    private DcIndexStageEvidence(){}
    private static final long MAX_INTENT_BYTES=16L*1024*1024;
    public static boolean hasStageIntent(Path stageFolder)throws java.io.IOException{
        Path folder=stageFolder.toAbsolutePath().normalize();if(!Files.exists(folder,LinkOption.NOFOLLOW_LINKS))return false;
        if(Files.isSymbolicLink(folder)||!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))throw new java.io.IOException("D023 stage evidence folder is not a regular directory");
        int found=0;try(DirectoryStream<Path> files=Files.newDirectoryStream(folder,"*-intent.json")){for(Path file:files){
            if(++found>1||Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_INTENT_BYTES)
                throw new java.io.IOException("D023 stage intent set is ambiguous or outside its file bound");}}
        return found==1;
    }
}
