package com.zoutrankil.data.index.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.index.storage.ThsMemberReadRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TUSHARE_PAGE_LIVE", matches="1")
class ThsMemberComparisonLiveTest {
    @Test void compareRealOneBoardSourceToStoredBusinessValuesWithoutWriting() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            String board = "885800.TI";
            var source = new ThsMemberSource(context.getBean(TusharePageService.class),
                    Path.of("artifacts/java-migration/D006/compare-source"))
                    .fetchBoard(board, Instant.now().truncatedTo(ChronoUnit.MICROS), () -> false);
            var columns = ThsMemberDataset.DEFINITION.columns().stream()
                    .map(DatasetDefinition.Column::logicalName).toList();
            var query = new DatasetReadQuery(columns, Map.of("board_code", board), null, null, null, 250, null);
            var old = new HashMap<ThsMember.Key, ThsMember>();
            do {
                var page = context.getBean(ThsMemberReadRepository.class).findPage(query);
                page.rows().forEach(row -> assertNull(old.put(row.key(), row)));
                if (!page.hasMore()) break;
                query = query.after(page.nextCursor());
            } while (true);
            var incoming = new HashMap<ThsMember.Key, ThsMember>();
            source.rows().forEach(row -> assertNull(incoming.put(row.key(), row)));
            int added = 0, removed = 0, revised = 0, identical = 0;
            var changedExamples = new ArrayList<String>();
            for (var entry : incoming.entrySet()) {
                var previous = old.get(entry.getKey());
                if (previous == null) added++;
                else if (sameBusinessValues(previous, entry.getValue())) identical++;
                else { revised++; if (changedExamples.size() < 10) changedExamples.add(entry.getKey().toString()); }
            }
            for (var key : old.keySet()) if (!incoming.containsKey(key)) removed++;
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("board", board); evidence.put("sourceRows", incoming.size());
            evidence.put("storedRows", old.size()); evidence.put("added", added);
            evidence.put("removed", removed); evidence.put("revised", revised);
            evidence.put("identical", identical); evidence.put("changedExamples", changedExamples);
            evidence.put("sourceFingerprint", source.sourceFingerprint());
            evidence.put("sourceReceipt", source.responseEvidence()); evidence.put("questdbWrites", 0);
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                    Path.of("artifacts/java-migration/D006/board-comparison.json").toFile(), evidence);
            assertEquals(incoming.size(), added + revised + identical);
            assertEquals(old.size(), removed + revised + identical);
        }
    }

    private static boolean sameBusinessValues(ThsMember a, ThsMember b) {
        return Objects.equals(a.constituentName(), b.constituentName())
                && Objects.equals(a.weight(), b.weight())
                && Objects.equals(a.inDate(), b.inDate())
                && Objects.equals(a.outDate(), b.outDate())
                && Objects.equals(a.isNew(), b.isNew());
    }
}
