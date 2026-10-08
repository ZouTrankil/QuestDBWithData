package com.zoutrankil.data.config;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.calendar.port.ExchangeCalendarReadPort;
import com.zoutrankil.data.calendar.port.SseCalendarWindowReadPort;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.SyncJobOwner;
import com.zoutrankil.data.flow.application.*;
import com.zoutrankil.data.flow.port.*;
import com.zoutrankil.data.margin.application.*;
import com.zoutrankil.data.margin.port.*;
import com.zoutrankil.data.service.DatasetRegistry;
import com.zoutrankil.data.service.ReadBindingCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FlowMarginApplicationWiringTest {
    @TempDir Path temp;
    private record Binding(Class<?> type, String property, String defaultTable) {}
    private static final List<Binding> BINDINGS = List.of(
            new Binding(MoneyflowTarget.class, "moneyflow", "java_d024_moneyflow_acceptance"),
            new Binding(MoneyflowThsTarget.class, "moneyflow-ths", "java_d025_moneyflow_ths_acceptance"),
            new Binding(MoneyflowDcTarget.class, "moneyflow-dc", "java_d026_moneyflow_dc_acceptance"),
            new Binding(MoneyflowHsgtTarget.class, "moneyflow-hsgt", "java_d027_moneyflow_hsgt_acceptance"),
            new Binding(MarginAllTarget.class, "margin-all", "java_d028_margin_all_acceptance"),
            new Binding(MarginDetailTarget.class, "margin-detail", "java_d029_margin_detail_acceptance"),
            new Binding(MarginSecsTarget.class, "margin-secs", "java_d030_margin_secs_acceptance"),
            new Binding(MarginZrzTarget.class, "margin-zrz", "java_d031_margin_zrz_acceptance"));

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void eightTargetsAndOwnersAssembleWithDefaultOrOverriddenTablesWithoutStorageIo(boolean override) throws Exception {
        var dataSource = mock(DataSource.class);
        Path ledger = temp.resolve("must-not-exist.sqlite3");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("app.sync.ledger-path", ledger.toString());
        properties.put("spring.main.banner-mode", "off");
        if (override) for (var binding : BINDINGS)
            properties.put("app.sync." + binding.property() + "-table",
                    binding.defaultTable().replace("_acceptance", "_wiring"));
        var application = new SpringApplication(QuestDataApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("dataSource", dataSource);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("flow-margin-wiring", properties));
        });
        try (var context = application.run("list-sync-jobs")) {
            for (var binding : BINDINGS) {
                assertEquals(1, context.getBeansOfType(binding.type()).size(), binding.type().getName());
                String expected = override ? binding.defaultTable().replace("_acceptance", "_wiring") : binding.defaultTable();
                assertEquals(expected, binding.type().getMethod("tableName").invoke(context.getBean(binding.type())));
            }
            for (var type : List.of(MoneyflowJobService.class, MoneyflowThsJobService.class,
                    MoneyflowDcJobService.class, MoneyflowHsgtJobService.class, MarginAllJobService.class,
                    MarginDetailJobService.class, MarginSecsJobService.class, MarginZrzJobService.class,
                    ExchangeCalendarReadPort.class, SseCalendarWindowReadPort.class)) {
                assertEquals(1, context.getBeansOfType(type).size(), type.getName());
            }
            var owners = context.getBeansOfType(SyncJobOwner.class).values();
            for (var dataset : List.of("moneyflow", "moneyflow_ths", "moneyflow_dc", "moneyflow_hsgt",
                    "margin_all", "margin_detail", "margin_secs", "margin_zrz")) {
                assertEquals(1, owners.stream().filter(owner -> owner.datasetId().equals(dataset)).count(), dataset);
            }
            assertFalse(MarginZrzSyncJobOwner.DEFINITION.enabled());
            assertFalse(MarginZrzSyncJobOwner.DEFINITION.dailyEligible());
            var datasets = context.getBean(DatasetRegistry.class);
            assertEquals(56, datasets.definitions().stream()
                    .filter(value -> value.capabilities().contains(DatasetDefinition.Capability.READ)).count());
            assertEquals(56, context.getBean(ReadBindingCatalog.class).bind(datasets).size());
            assertFalse(context.getBeanFactory().containsSingleton("questDbClient"));
            verify(dataSource, never()).getConnection();
            verify(dataSource, never()).getConnection(anyString(), anyString());
            assertFalse(Files.exists(ledger));
        }
        assertFalse(Files.exists(ledger));
    }
}
