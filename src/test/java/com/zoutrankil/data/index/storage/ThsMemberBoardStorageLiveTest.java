package com.zoutrankil.data.index.storage;

import com.zoutrankil.data.repository.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.JobDefinitionJson;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="QUESTDB_BOUNDED_READ", matches="1")
class ThsMemberBoardStorageLiveTest {
    @Test void formalTableCanBeFingerprintedWithBoundedBoardMaterialization() throws Exception {
        var app = new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c -> c.addBeanFactoryPostProcessor(f ->
                ((BeanDefinitionRegistry) f).removeBeanDefinition("commandLineRunner")));
        try (var context = app.run()) {
            var snapshot = new ThsMemberBoardStorage(context.getBean(JdbcTemplate.class), "ths_member")
                    .snapshot("885800.TI");
            assertEquals(506, snapshot.boardRows().size());
            assertEquals(416612, snapshot.totalRows());
            JobDefinitionJson.mapper().writerWithDefaultPrettyPrinter().writeValue(
                    Path.of("artifacts/java-migration/D006/physical-scan.json").toFile(),
                    Map.of("physicalId", snapshot.identity().id(), "physicalDirectory", snapshot.identity().directory(),
                            "writerTxn", snapshot.identity().writerTxn(),
                            "board", snapshot.board(), "boardRows", snapshot.boardRows().size(),
                            "otherRows", snapshot.otherRows(), "otherFingerprint", snapshot.otherFingerprint(),
                            "contentFingerprint", snapshot.contentFingerprint(), "questdbWrites", 0));
        }
    }
}
