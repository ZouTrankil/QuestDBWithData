package com.zoutrankil.data.index.port;
import com.zoutrankil.data.domain.*;
import java.util.*;
import java.time.*;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import com.zoutrankil.data.index.domain.*;
public interface IndexMonthlyWriteSession extends com.zoutrankil.data.sync.port.VerifiedWriteSession<IndexMonthly,IndexMonthlyKey> {
 @FunctionalInterface interface PublicationCallback {String publish() throws Exception;}
 void useStagingTarget(String stage,String physicalId);
 void configureFinalPublication(List<IndexMonthly> rows,PublicationCallback callback);
 boolean publicationComplete();
 List<IndexMonthly> readExistingRows(String code);
 IndexMonthlyState.TargetRange readExistingRange(String code);
 List<IndexMonthly> readRange(String code,LocalDate from,LocalDate to);
}
