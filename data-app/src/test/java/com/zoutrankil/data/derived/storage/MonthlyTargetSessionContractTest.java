package com.zoutrankil.data.derived.storage;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.domain.*;
import com.zoutrankil.data.derived.mapper.*;
import com.zoutrankil.data.derived.port.*;
import java.io.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonthlyTargetSessionContractTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void blankTargetStaysLazyAndFreshWritersNeverShareMutableStopOrUnknownFlags(boolean macro)throws Exception {
        var ds=mock(DataSource.class);var jdbc=new JdbcTemplate(ds);var props=new QuestDbProperties();
        if(macro) {
            var blank=new QuestDbMacroCoreMonthlyTarget(jdbc,props,"  ");assertEquals("",blank.table());assertThrows(IllegalArgumentException.class,blank::newWriter);
            var target=new QuestDbMacroCoreMonthlyTarget(jdbc,props," java_d104_macro_core_monthly_unit ");var a=target.newWriter();var b=target.newWriter();
            assertEquals("java_d104_macro_core_monthly_unit",target.table());assertNotSame(a,b);assertSame(a.codec(),b.codec());
            mark(a,"unresolved",true);mark(a,"senderStopped",true);assertTrue(a.unresolved());assertFalse(b.unresolved());assertTrue(a.uncertainSenderStopped());assertFalse(b.uncertainSenderStopped());
        } else {
            var blank=new QuestDbEquityStyleMonthlyTarget(jdbc,props,null);assertEquals("",blank.table());assertThrows(IllegalArgumentException.class,blank::newWriter);
            var target=new QuestDbEquityStyleMonthlyTarget(jdbc,props," java_d103_equity_style_monthly_unit ");var a=target.newWriter();var b=target.newWriter();
            assertEquals("java_d103_equity_style_monthly_unit",target.table());assertNotSame(a,b);assertSame(a.codec(),b.codec());
            mark(a,"unresolved",true);mark(a,"senderStopped",true);assertTrue(a.unresolved());assertFalse(b.unresolved());assertTrue(a.uncertainSenderStopped());assertFalse(b.uncertainSenderStopped());
        }
        verifyNoInteractions(ds);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void pureCodecMatchesIndependentYearMonthBitmapAndRawDoubleFrame(boolean macro)throws Exception {
        List<String> fields=macro?MacroCoreMonthlyDataset.STORAGE_COLUMNS:EquityStyleMonthlyDataset.STORAGE_COLUMNS;
        var values=new LinkedHashMap<String,Object>();values.put("month",LocalDate.of(2026,6,1));
        for(int i=1;i<fields.size();i++){Double value=null;if(i==1)value=-0.0;else if(i%3!=0)value=i+0.125;values.put(fields.get(i),value);}
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(2026);out.writeByte(6);int nulls=0;
        for(int i=1;i<fields.size();i++)if(values.get(fields.get(i))==null)nulls|=1<<(i-1);out.writeInt(nulls);
        for(int i=1;i<fields.size();i++)if(values.get(fields.get(i))!=null)out.writeLong(Double.doubleToRawLongBits((Double)values.get(fields.get(i))));out.flush();
        if(macro) {
            var row=new MacroCoreMonthlyMapper().fromValues(values);assertEquals(9,fields.size());
            assertArrayEquals(bytes.toByteArray(),MacroCoreMonthlyWriteSession.CODEC.canonicalBytes(row));assertSame(MacroCoreMonthlyWriteSession.CODEC,MacroCoreMonthlyWritePort.CODEC);
            assertEquals(values,MacroCoreMonthlyRows.values(row).asMap());assertEquals(bytes.size()*4+2048,MacroCoreMonthlyWriteSession.CODEC.estimatedTransportBytes(row,bytes.toByteArray()));
        } else {
            var row=new EquityStyleMonthlyMapper().fromValues(values);assertEquals(30,fields.size());
            assertArrayEquals(bytes.toByteArray(),EquityStyleMonthlyWriteSession.CODEC.canonicalBytes(row));assertSame(EquityStyleMonthlyWriteSession.CODEC,EquityStyleMonthlyWritePort.CODEC);
            assertEquals(values,EquityStyleMonthlyRows.values(row).asMap());assertEquals(bytes.size()*4+2048,EquityStyleMonthlyWriteSession.CODEC.estimatedTransportBytes(row,bytes.toByteArray()));
        }
    }

    @Test void publicationAdmissionRetainsFamilyNullRulesAndExactDuplicateOrder() {
        var equity=new LinkedHashMap<String,Object>();EquityStyleMonthlyDataset.STORAGE_COLUMNS.forEach(k->equity.put(k,k.equals("month")?LocalDate.of(2026,6,1):null));
        var empty=new EquityStyleMonthlyMapper().fromValues(equity);
        assertEquals("A timestamp-only all-null row cannot be transported as ILP",assertThrows(IllegalArgumentException.class,()->EquityStyleMonthlyRows.requireBatch(List.of(empty))).getMessage());
        equity.put("hs300_ret_1m",-0.0);var valid=new EquityStyleMonthlyMapper().fromValues(equity);assertDoesNotThrow(()->EquityStyleMonthlyRows.requireBatch(List.of(valid)));
        assertEquals("Duplicate complete month key",assertThrows(IllegalArgumentException.class,()->EquityStyleMonthlyRows.requireBatch(List.of(valid,valid))).getMessage());
        var macro=new LinkedHashMap<String,Object>();MacroCoreMonthlyDataset.STORAGE_COLUMNS.forEach(k->macro.put(k,k.equals("month")?LocalDate.of(2026,6,1):null));
        var missing=new MacroCoreMonthlyMapper().fromValues(macro);
        assertEquals("Complete six-field monthly publication required: cpi_yoy",assertThrows(IllegalArgumentException.class,()->MacroCoreMonthlyRows.requireBatch(List.of(missing))).getMessage());
        MacroCoreMonthlyDataset.REQUIRED_MONTHLY_FIELDS.forEach(k->macro.put(k,1D));var complete=new MacroCoreMonthlyMapper().fromValues(macro);assertDoesNotThrow(()->MacroCoreMonthlyRows.requireBatch(List.of(complete)));
        assertEquals("Duplicate complete month key",assertThrows(IllegalArgumentException.class,()->MacroCoreMonthlyRows.requireBatch(List.of(complete,complete))).getMessage());
    }

    static void mark(Object value,String name,boolean flag)throws Exception {var field=value.getClass().getDeclaredField(name);field.setAccessible(true);field.setBoolean(value,flag);}
}
