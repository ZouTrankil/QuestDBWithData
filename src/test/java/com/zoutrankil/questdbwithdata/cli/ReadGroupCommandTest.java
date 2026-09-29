package com.zoutrankil.questdbwithdata.cli;

import com.zoutrankil.questdbwithdata.domain.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadGroupCommandTest {
    @Test void requestFileRoutesToReaderAndFailedMemberMakesCommandFail() throws Exception {
        var reader = mock(ReadGroupReader.class);
        var cli = new CommandLineRunner(mock(StockBasicSyncService.class), mock(DatasetRegistry.class),
                mock(SyncJobRegistry.class), mock(StockBasicJobService.class), mock(StockBasicGroupService.class), reader, mock(StockBasicWriteGroupService.class));
        assertThrows(IllegalArgumentException.class, () -> cli.run(new DefaultApplicationArguments("read-dataset-group")));
        verifyNoInteractions(reader);
        var request = new ReadGroupRequest(List.of(new ReadGroupRequest.Member("sample", "stock_basic_snapshot", 1,
                new DatasetReadQuery(List.of("ts_code"), Map.of(), null, null, null, 1, null))), Duration.ofSeconds(10));
        when(reader.readRequest(Path.of("request.json"))).thenReturn(request);
        when(reader.read(eq(request), any())).thenReturn(new ReadGroupReader.Result(Instant.now(), Instant.now(),
                List.of(new ReadGroupReader.MemberResult("sample", "stock_basic_snapshot", 1, DatasetValues.class,
                        ReadGroupReader.Status.FAILED, null, "InjectedQueryFailure"))));
        assertThrows(IllegalStateException.class, () -> cli.run(new DefaultApplicationArguments(
                "read-dataset-group", "--request", "request.json")));
        verify(reader).readRequest(Path.of("request.json")); verify(reader).read(eq(request), any());
    }
}
