package com.zoutrankil.data.index.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.repository.ReferencePublicationJournal;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.sql.DriverManager;
import static com.zoutrankil.data.index.application.DcIndexPublicationContractTest.*;
import static org.junit.jupiter.api.Assertions.*;

class DcIndexPublicationVersionContractTest {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void retainedVersionOneAndTwoEvidenceRemainReadableButUnknownVersionCannotRename(int version)throws Exception {
        var f=new Fixture(temp,false);f.tables.fault=Fault.BEFORE_FIRST;assertThrows(Stop.class,f::publish);f.tables.fault=null;
        // Materialize the historical v1 scope independently: v1 had neither version nor stagePhysicalTarget.
        var json=JobDefinitionJson.mapper();ObjectNode intent=json.valueToTree(f.journal().intent());
        ObjectNode scope=(ObjectNode)json.readTree(intent.path("scope").asText());
        if(version==1){scope.remove("proofVersion");scope.remove("stagePhysicalTarget");}else scope.put("proofVersion",version);
        intent.put("scope",json.writeValueAsString(scope));
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+f.path);var sql=db.prepareStatement("UPDATE reference_publications SET intent_json=? WHERE run_id=?")){
            sql.setString(1,json.writeValueAsString(intent));sql.setString(2,RUN);assertEquals(1,sql.executeUpdate());
        }
        if(version<=2){f.publisher.finish(RUN,true);assertEquals(ReferencePublicationJournal.State.VERIFIED,f.journal().state());assertEquals(2,f.tables.renames);}
        else{assertThrows(IllegalStateException.class,()->f.publisher.finish(RUN,true));assertEquals(0,f.tables.renames);
            assertEquals(ReferencePublicationJournal.State.PREPARED,f.journal().state());assertNotNull(f.locks.findOwned(RUN,f.scope()));}
        f.assertMutexFree();
    }
}
