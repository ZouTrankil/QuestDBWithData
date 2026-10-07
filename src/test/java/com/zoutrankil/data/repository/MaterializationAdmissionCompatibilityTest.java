package com.zoutrankil.data.repository;

import com.zoutrankil.data.config.QuestDbProperties;
import com.zoutrankil.data.domain.JobDefinitionJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaterializationAdmissionCompatibilityTest {
    @TempDir Path temporary;
    private static final String TARGET="questdb-"+"a".repeat(64);

    @Test void portsKeepDistinctSpecsAndReattestEveryPrivateAdmission() throws Exception {
        var specs=new ArrayList<PrivateQuestDbInstanceAttestor.Spec>();
        try(var constructors=mockConstruction(PrivateQuestDbInstanceAttestor.class,
                (mock,context)->specs.add((PrivateQuestDbInstanceAttestor.Spec)context.arguments().getFirst()))) {
            for(boolean retail:new boolean[]{false,true}) {
                var jdbc=mock(JdbcTemplate.class);
                var properties=properties(retail);
                var port=port(retail,jdbc,properties,true,null);
                assertEquals(retail?2:1,constructors.constructed().size());
                var attestor=constructors.constructed().getLast();
                call(port,"verifyPrivateInstance");call(port,"verifyPrivateInstance");
                verify(attestor,times(2)).attest();
                verifyNoInteractions(jdbc);
                var spec=specs.getLast();
                assertEquals(retail?"D098":"D095",spec.datasetCode());
                assertEquals(Path.of("var"),spec.workspaceVar());
                assertEquals(Path.of("var",retail?"d098-isolated-questdb":"d095-isolated-questdb"),spec.dataRoot());
                assertEquals(retail?18822:18812,spec.pgPort());
                assertEquals(retail?19010:19000,spec.qwpPort());
            }
        }
    }

    @Test void disabledRemoteAndWrongPortSettingsFailBeforeAttestationOrSql() throws Exception {
        try(var constructors=mockConstruction(PrivateQuestDbInstanceAttestor.class)) {
            for(boolean retail:new boolean[]{false,true})for(int invalid=0;invalid<4;invalid++) {
                var jdbc=mock(JdbcTemplate.class);var properties=properties(retail);
                if(invalid==1)properties.setHost("192.0.2.1");
                if(invalid==2)properties.setPgPort(8812);
                if(invalid==3)properties.setQwpPort(9000);
                var port=port(retail,jdbc,properties,invalid!=0,null);
                String expected=(retail?"D098":"D095")+" mutations require the explicitly enabled local private instance on "
                        +(retail?"18822/19010":"18812/19000");
                assertEquals(expected,assertThrows(IllegalStateException.class,()->call(port,"verifyPrivateInstance")).getMessage());
                verifyNoInteractions(constructors.constructed().getLast(),jdbc);
            }
        }
    }

    @Test void explicitTargetAdmissionDoesNotBypassPrivateInstallationOrFullRepair() throws Exception {
        try(var constructors=mockConstruction(PrivateQuestDbInstanceAttestor.class)) {
            for(boolean retail:new boolean[]{false,true}) {
                var jdbc=mock(JdbcTemplate.class);var port=spy(port(retail,jdbc,properties(retail),true,TARGET));
                if(port instanceof RetailSentimentDailyV1MaterializationPort p)doReturn(TARGET).when(p).targetId();
                else doReturn(TARGET).when((MarketBreadthDailyV1MaterializationPort)port).targetId();
                var attestor=constructors.constructed().getLast();
                call(port,"requireAdmittedMutation");verifyNoInteractions(attestor,jdbc);
                var rejection=new IllegalStateException("private proof rejected");
                doThrow(rejection).when(attestor).attest();
                for(String method:List.of("verifyPrivateInstance","createIsolatedTarget","configureFullIsolated","fullSourceScope"))
                    assertSame(rejection,assertThrows(IllegalStateException.class,()->call(port,method)));
                verify(attestor,times(4)).attest();verifyNoInteractions(jdbc);
            }
        }
    }

    @Test void retailFixtureCheckStillRequiresItsOwnTaskRootAndExactlyItsTwoTables() throws Exception {
        var callbacks=new ArrayList<PrivateQuestDbInstanceAttestor.RootCheck>();
        try(var constructors=mockConstruction(PrivateQuestDbInstanceAttestor.class,
                (mock,context)->callbacks.add((PrivateQuestDbInstanceAttestor.RootCheck)context.arguments().get(1)))) {
            port(true,mock(JdbcTemplate.class),properties(true),true,null);
            var check=callbacks.getFirst();assertNotNull(check);
            Path root=Files.createDirectory(temporary.resolve("d098")).toRealPath();
            writeMarker(root,"D098",root,List.of(RetailSentimentDailyV1MaterializationPort.SOURCE,
                    RetailSentimentDailyV1MaterializationPort.OUTPUT));
            assertDoesNotThrow(()->check.verify(root));
            writeMarker(root,"D095",root,List.of("l2_daily_features","mv_retail_sentiment_daily_v1"));
            assertEquals("D098 private fixture marker differs from the admitted source and MV",
                    assertThrows(IllegalStateException.class,()->check.verify(root)).getMessage());
            writeMarker(root,"D098",root,List.of("l2_daily_features","l2_daily_features"));
            assertThrows(IllegalArgumentException.class,()->check.verify(root));
            writeMarker(root,"D098",temporary.toRealPath(),List.of("l2_daily_features","mv_retail_sentiment_daily_v1"));
            assertThrows(IllegalStateException.class,()->check.verify(root));
        }
    }

    private static Object port(boolean retail,JdbcTemplate jdbc,QuestDbProperties properties,boolean enabled,String expected) {
        return retail?new RetailSentimentDailyV1MaterializationPort(jdbc,properties,enabled,expected)
                :new MarketBreadthDailyV1MaterializationPort(jdbc,properties,enabled,expected);
    }
    private static QuestDbProperties properties(boolean retail) {
        var result=new QuestDbProperties();result.setPgPort(retail?18822:18812);result.setQwpPort(retail?19010:19000);return result;
    }
    private static void call(Object target,String method) throws Exception {
        var member=target.getClass().getDeclaredMethod(method);member.setAccessible(true);
        try { member.invoke(target); }
        catch(InvocationTargetException failure) {
            if(failure.getCause() instanceof Exception exception)throw exception;
            throw (Error)failure.getCause();
        }
    }
    private static void writeMarker(Path root,String task,Path declaredRoot,List<String> tables) throws Exception {
        Files.write(root.resolve("d098-fixture.json"),JobDefinitionJson.mapper().writeValueAsBytes(
                Map.of("task_id",task,"data_root",declaredRoot.toString(),"fixture_tables",tables)));
    }
}
