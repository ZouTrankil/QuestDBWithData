package com.zoutrankil.data.l2.application;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.DatasetWritePreparation;
import com.zoutrankil.data.service.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static com.zoutrankil.data.l2.application.L2EventAndT0Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class L2EventAndT0PreparedContractTest {
    @ParameterizedTest @EnumSource(Family.class)
    @SuppressWarnings({"rawtypes", "unchecked"})
    void everyEntryCertifiesFullSourceBeforeDelegateAndRetainsFailure(Family family) throws Exception {
        var events=new ArrayList<String>();var refuse=new AtomicBoolean();var rejection=new IOException("changed source");Object row=family.row(family.input());
        var definition=family==Family.EVENT?L2EventResponseFeaturesDataset.DEFINITION:L2T0TrainingLabelsDataset.DEFINITION;
        var batch=DatasetWritePreparation.prepare(definition,List.of(row),family::values,new DatasetWritePreparation.Limits(10000,16*1024*1024));
        var member=new WriteGroupPlan.Member("member","member-batch","questdb-contract",definition,batch);
        var request=family.definition().freeze(SyncJobDefinition.Mode.INGEST,Map.of("symbols",List.of(SYMBOL),"source_root_id",ROOT),DAY,DAY,DAY);
        var expected=new SyncJobRunner.Result("child",SyncRunState.VERIFIED,1,1,null);
        PreparedWriteAdapter delegate=mock(PreparedWriteAdapter.class,invocation->{return switch(invocation.getMethod().getName()){
            case "member" -> member;
            case "request" -> request;
            case "preflight" -> {events.add("delegate-preflight");yield null;}
            case "execute" -> {assertEquals("child",invocation.getArgument(2));assertEquals("parent",invocation.getArgument(3));assertEquals("prior",invocation.getArgument(4));events.add("delegate-execute");yield expected;}
            case "revalidate" -> {events.add("delegate-revalidate");yield "proof";}
            default -> RETURNS_DEFAULTS.answer(invocation);
        };});
        var certify=(org.mockito.stubbing.Answer<Void>)invocation->{assertEquals(List.of(row),invocation.getArgument(0));events.add("certify");if(refuse.get())throw rejection;return null;};
        WriteGroupMemberAdapter wrapper;
        if(family==Family.EVENT){var owner=mock(L2EventResponseFeaturesJobService.class);doAnswer(certify).when(owner).verifyPreparedWriteRows(anyList());wrapper=new L2EventResponseFeaturesPreparedWriteAdapter(owner,delegate);}
        else {var owner=mock(L2T0TrainingLabelsJobService.class);doAnswer(certify).when(owner).verifyPreparedWriteRows(anyList());wrapper=new L2T0TrainingLabelsPreparedWriteAdapter(owner,delegate);}
        assertSame(member,wrapper.member());assertSame(request,wrapper.request());
        wrapper.preflight(request);assertEquals(List.of("certify","delegate-preflight"),events);events.clear();
        assertSame(expected,wrapper.execute(null,null,"child","parent","prior","questdb-contract",request,()->false));assertEquals(List.of("certify","delegate-execute"),events);events.clear();
        assertEquals("proof",wrapper.revalidate(null,"prior","questdb-contract",request,()->false,Path.of("unused")));assertEquals(List.of("certify","delegate-revalidate"),events);events.clear();
        refuse.set(true);
        assertSame(rejection,assertThrows(IOException.class,()->wrapper.preflight(request)));
        assertSame(rejection,assertThrows(IOException.class,()->wrapper.execute(null,null,"child","parent","prior","questdb-contract",request,()->false)));
        assertSame(rejection,assertThrows(IOException.class,()->wrapper.revalidate(null,"prior","questdb-contract",request,()->false,Path.of("unused"))));
        assertEquals(List.of("certify","certify","certify"),events);
    }
}
