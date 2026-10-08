package com.zoutrankil.data.derived.port;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
/** Physical table operations and read-only discovery for the application publication protocol. */
public interface NativeDailyPublicationTables {
    interface EntryFields {
        String intentJson()throws Exception;
        String state()throws Exception;
        long revision()throws Exception;
    }
    @FunctionalInterface interface Decoder<T> { T decode(EntryFields fields)throws Exception; }
    void requireNoPendingPublication(Path ledgerPath,String dataset)throws SQLException;
    <T> Optional<T> findForRun(Path ledgerPath,String dataset,String runId,Decoder<T> decoder)throws Exception;
    void rename(String from,String to);boolean tableExists(String table);boolean identityMatches(String table,long id);
}
