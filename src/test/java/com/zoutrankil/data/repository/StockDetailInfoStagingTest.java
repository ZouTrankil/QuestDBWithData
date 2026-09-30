package com.zoutrankil.data.repository;

import com.zoutrankil.data.domain.StockDetailInfo;
import com.zoutrankil.data.mapper.StockDetailInfoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockDetailInfoStagingTest {
    @TempDir Path root;
    private StockDetailInfo row() {
        return new StockDetailInfo("000001.SZ",Instant.EPOCH,"000001","sample",null,null,"L",null,
                null,null,null,null,null,null,null,null,null,null);
    }
    @Test void preflightCancellationDoesNotCreateTableOrConnect() throws Exception {
        var data=mock(DataSource.class);var writer=new StockDetailInfoStaging(new JdbcTemplate(data));
        var before=new StockDetailInfoStorage.Snapshot(new StockDetailInfoStorage.Identity(1,"test"),List.of(),"test",0);
        var prepared=StockDetailInfoStaging.prepare(before,List.of(row()));
        assertThrows(java.util.concurrent.CancellationException.class,()->writer.write(prepared,root,()->true));
        verify(data,never()).getConnection();
    }
    @Test void unchangedRowsPreserveOriginalPhysicalObjectsAndDoNotConnect() throws Exception {
        var data=mock(DataSource.class);var writer=new StockDetailInfoStaging(new JdbcTemplate(data));
        var original=new StockDetailInfoMapper().toStorage(row());
        var before=new StockDetailInfoStorage.Snapshot(new StockDetailInfoStorage.Identity(1,"test"),List.of(original),"test",0);
        var prepared=StockDetailInfoStaging.prepare(before,List.of(row()));
        assertSame(original,prepared.rows().getFirst());
        assertFalse(prepared.merge().requiresPublication());
        assertThrows(IllegalArgumentException.class,()->writer.write(prepared,root));
        verify(data,never()).getConnection();
    }
}
