package com.zoutrankil.data.index.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.port.*;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes","unchecked"})
class IndexCoverageCodecContractTest {
    enum Family { MARKET,BASIC,WEIGHT }
    @ParameterizedTest @EnumSource(Family.class)
    void comparisonUsesTheSessionCodecWithOriginalSizeDuplicateAndByteRules(Family f)throws Exception {
        VerifiedWriteSession writer;Object left,right;Object owner=null;Method method;
        var day=LocalDate.of(2020,1,3);
        switch(f) {
            case MARKET->{
                writer=mock(IndexDailyMarketWriteSession.class);var a=mock(IndexDailyMarket.class);var b=mock(IndexDailyMarket.class);
                var key=new IndexDailyMarketKey("000300.SH",day);when(a.key()).thenReturn(key);when(b.key()).thenReturn(key);left=a;right=b;
                method=IndexDailyMarketCoverage.class.getDeclaredMethod("sameRows",List.class,List.class,IndexDailyMarketWriteSession.class);
            }
            case BASIC->{
                writer=mock(IndexDailyBasicWriteSession.class);var a=mock(IndexDailyBasic.class);var b=mock(IndexDailyBasic.class);
                var key=new IndexDailyBasicKey("000300.SH",day);when(a.key()).thenReturn(key);when(b.key()).thenReturn(key);left=a;right=b;
                method=IndexDailyBasicCoverage.class.getDeclaredMethod("sameRows",List.class,List.class,IndexDailyBasicWriteSession.class);
            }
            case WEIGHT->{
                writer=mock(IndexWeightWriteSession.class);var a=mock(IndexWeight.class);var b=mock(IndexWeight.class);
                var key=new IndexWeightKey("000300","000001.SZ",day);when(a.key()).thenReturn(key);when(b.key()).thenReturn(key);left=a;right=b;
                owner=new IndexWeightSyncAdapter(mock(IndexWeightSource.class),(IndexWeightWriteSession)writer,Path.of("unused-evidence"));
                method=IndexWeightSyncAdapter.class.getDeclaredMethod("sameRows",List.class,List.class);
            }
            default->throw new AssertionError();
        }
        method.setAccessible(true);var codec=mock(VerifiedBatchExecutor.Codec.class);when(writer.codec()).thenReturn(codec);
        when(codec.canonicalBytes(left)).thenReturn(new byte[]{1,0,2});when(codec.canonicalBytes(right)).thenReturn(new byte[]{1,0,2});
        assertTrue(compare(method,owner,writer,List.of(left),List.of(right)));
        verify(codec).canonicalBytes(left);verify(codec).canonicalBytes(right);
        when(codec.canonicalBytes(right)).thenReturn(new byte[]{1,2});
        assertFalse(compare(method,owner,writer,List.of(left),List.of(right)));
        assertFalse(compare(method,owner,writer,List.of(left,left),List.of(right,right)));
        clearInvocations(codec,writer);
        assertFalse(compare(method,owner,writer,List.of(left),List.of()));verifyNoInteractions(codec,writer);
        assertTrue(compare(method,owner,writer,List.of(),List.of()));verifyNoInteractions(codec,writer);
        clearInvocations(codec,writer);assertFalse(compare(method,owner,writer,List.of(left,left),List.of(right,right)));
        verify(codec,times(2)).canonicalBytes(left);verify(codec,never()).canonicalBytes(right);
    }
    private static boolean compare(Method m,Object owner,VerifiedWriteSession writer,List<?> left,List<?> right)throws Exception {
        return (boolean)(owner==null?m.invoke(null,left,right,writer):m.invoke(owner,left,right));
    }
}
