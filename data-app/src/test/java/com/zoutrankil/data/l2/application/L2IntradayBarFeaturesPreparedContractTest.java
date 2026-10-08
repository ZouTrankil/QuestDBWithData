package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.l2.mapper.L2IntradayBarFeaturesMapper;
import com.zoutrankil.data.l2.port.L2IntradayBarFeaturesTarget;
import com.zoutrankil.data.repository.DatasetWritePreparation;
import com.zoutrankil.data.service.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class L2IntradayBarFeaturesPreparedContractTest {
    @TempDir Path temp;
    private static final LocalDate DAY=LocalDate.of(2026,9,21);

    @Test void validPreparedRowsUseActualSeparateTenThousandRowAndFileBounds()throws Exception{
        var h=new Harness(temp);h.owner.verifyPreparedWriteRows(h.rows);
        verify(h.source).inspect(DAY,DAY,List.of("000001.SZ"),10000,10000);
        verify(h.source).stream(same(h.inspection()),eq(List.of("000001.SZ")),eq(10000),eq(10000),any(),any(),eq(Duration.ofHours(2)));
        verify(h.target).tableName();verifyNoMoreInteractions(h.target);assertFalse(Files.exists(temp.resolve("ledger.sqlite3")));
    }

    @ParameterizedTest @ValueSource(strings={"duplicate","empty","too_many","too_wide"})
    void invalidPreparedScopeRejectsBeforeCalendarSourceOrWriter(String kind)throws Exception{
        var h=new Harness(temp);List<L2IntradayBarFeatures> submitted=switch(kind){
            case "duplicate"->List.of(h.rows.getFirst(),h.rows.getFirst());case "empty"->List.of();
            case "too_many"->Collections.nCopies(10001,h.rows.getFirst());
            default->{var r=h.rows.getFirst();yield List.of(r,new L2IntradayBarFeatures(DAY.plusDays(31),r.symbol(),r.market(),r.board(),r.minute().plusSeconds(31*86400L),r.features()));}
        };
        assertThrows(IllegalArgumentException.class,()->h.owner.verifyPreparedWriteRows(submitted));
        assertEquals(0,h.calendarCalls);verifyNoInteractions(h.source);verify(h.target,never()).newWriter();
    }

    @ParameterizedTest @ValueSource(strings={"count","calendar","value","missing","duplicate_source"})
    void preparedSourceMismatchRefusesBeforeAnyPhysicalMutation(String kind)throws Exception{
        var h=new Harness(temp);
        if(kind.equals("count"))h.selectedRows=1;
        if(kind.equals("calendar"))h.closedCalendar=true;
        if(kind.equals("value")){var r=h.rows.getFirst();h.streamed=List.of(new L2IntradayBarFeatures(r.tradeDate(),r.symbol(),r.market(),"CHANGED",r.minute(),r.features()),h.rows.getLast());}
        if(kind.equals("missing"))h.streamed=List.of(h.rows.getFirst());
        if(kind.equals("duplicate_source"))h.streamed=List.of(h.rows.getFirst(),h.rows.getFirst());
        assertThrows(IllegalArgumentException.class,()->h.owner.verifyPreparedWriteRows(h.rows));verify(h.target,never()).newWriter();
        if(kind.equals("count")||kind.equals("calendar"))verify(h.source,never()).stream(any(),anyList(),anyInt(),anyInt(),any(),any(),any());
        assertFalse(Files.exists(temp.resolve("ledger.sqlite3")));
    }

    @Test void publicPreparedWrapperVerifiesSourceBeforeEveryDelegateOperation()throws Exception{
        var owner=mock(L2IntradayBarFeaturesJobService.class);
        @SuppressWarnings("unchecked") PreparedWriteAdapter<L2IntradayBarFeatures,L2IntradayBarFeaturesKey> delegate=mock(PreparedWriteAdapter.class);
        var rows=L2IntradayBarFeaturesFixtures.rows();var mapper=new L2IntradayBarFeaturesMapper();
        var batch=DatasetWritePreparation.prepare(L2IntradayBarFeaturesDataset.DEFINITION,rows,mapper::values,new DatasetWritePreparation.Limits(10000,16*1024*1024));
        var member=new WriteGroupPlan.Member("member","batch","target",L2IntradayBarFeaturesDataset.DEFINITION,batch);when(delegate.member()).thenReturn(member);
        var request=L2IntradayBarFeaturesJobService.definition().freeze(SyncJobDefinition.Mode.BACKFILL,Map.of("source_root_id","e".repeat(64),"symbols",List.of("000001.SZ","600001.SH")),DAY,DAY,DAY);
        when(delegate.request()).thenReturn(request);var adapter=new L2IntradayBarFeaturesPreparedWriteAdapter(owner,delegate);
        assertSame(member,adapter.member());assertSame(request,adapter.request());clearInvocations(owner,delegate);
        BooleanSupplier cancelled=()->false;Path evidence=temp.resolve("evidence");
        adapter.preflight(request);adapter.execute(null,null,"child","parent","prior","target",request,cancelled);
        adapter.revalidate(null,"prior","target",request,cancelled,evidence);
        var order=inOrder(owner,delegate);
        order.verify(delegate).member();order.verify(owner).verifyPreparedWriteRows(rows);order.verify(delegate).preflight(request);
        order.verify(delegate).member();order.verify(owner).verifyPreparedWriteRows(rows);order.verify(delegate).execute(null,null,"child","parent","prior","target",request,cancelled);
        order.verify(delegate).member();order.verify(owner).verifyPreparedWriteRows(rows);order.verify(delegate).revalidate(null,"prior","target",request,cancelled,evidence);
        clearInvocations(owner,delegate);doThrow(new IllegalArgumentException("source drift")).when(owner).verifyPreparedWriteRows(anyList());
        assertThrows(IllegalArgumentException.class,()->adapter.preflight(request));verify(delegate,never()).preflight(any());
        assertThrows(IllegalArgumentException.class,()->adapter.execute(null,null,"child","parent","prior","target",request,cancelled));
        verify(delegate,never()).execute(any(),any(),any(),any(),any(),any(),any(),any());
        assertThrows(IllegalArgumentException.class,()->adapter.revalidate(null,"prior","target",request,cancelled,evidence));
        verify(delegate,never()).revalidate(any(),any(),any(),any(),any(),any());
    }

    private static final class Harness{
        final L2IntradayBarFeaturesParquetSource source=mock(L2IntradayBarFeaturesParquetSource.class);
        final L2IntradayBarFeaturesTarget target=mock(L2IntradayBarFeaturesTarget.class);final L2IntradayBarFeaturesJobService owner;
        final List<L2IntradayBarFeatures> rows;List<L2IntradayBarFeatures> streamed;int selectedRows=2,calendarCalls;boolean closedCalendar;
        L2IntradayBarFeaturesParquetSource.Inspection lastInspection;
        Harness(Path folder)throws Exception{
            var r=L2IntradayBarFeaturesFixtures.rows().getFirst();rows=List.of(r,new L2IntradayBarFeatures(r.tradeDate(),r.symbol(),r.market(),r.board(),r.minute().plusSeconds(60),r.features()));streamed=rows;
            when(target.tableName()).thenReturn("java_d087_l2_intraday_bar_features_prepared");
            ExchangeCalendarReadPort calendar=query->{calendarCalls++;return new DatasetReadPage<>("exchange_calendar",1,"fixture",Instant.EPOCH,List.of(new ExchangeCalendar("SSE",DAY,!closedCalendar,DAY.minusDays(1))),null);};
            owner=new L2IntradayBarFeaturesJobService(calendar,target,source,folder.resolve("ledger.sqlite3"));
            when(source.inspect(any(),any(),anyList(),anyInt(),anyInt())).thenAnswer(a->{lastInspection=new L2IntradayBarFeaturesParquetSource.Inspection(DAY,DAY,List.of(DAY),selectedRows,selectedRows,1,1,100,"a".repeat(64),"b".repeat(64),"l2-intraday-bar-features-parquet-v1","e".repeat(64),true);return lastInspection;});
            doAnswer(a->{@SuppressWarnings("unchecked") var consumer=(SyncJobRunner.PageConsumer<L2IntradayBarFeatures>)a.getArgument(4);BooleanSupplier cancellation=a.getArgument(5);assertFalse(cancellation.getAsBoolean());
                consumer.accept(new SyncJobRunner.Page<>(streamed,"a".repeat(64),"synthetic receipt","cursor"));return new SyncJobRunner.SourceCompletion(1,streamed.size(),true,"synthetic completion");
            }).when(source).stream(any(),anyList(),anyInt(),anyInt(),any(),any(),any());
        }
        L2IntradayBarFeaturesParquetSource.Inspection inspection(){return lastInspection;}
    }
}
