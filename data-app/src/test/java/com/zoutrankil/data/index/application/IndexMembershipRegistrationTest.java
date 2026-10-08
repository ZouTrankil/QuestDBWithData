package com.zoutrankil.data.index.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.QuestDataApplication;
import com.zoutrankil.data.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexMembershipRegistrationTest {
    @Test void actualOwnerAndDatasetAreDiscoverableWithoutStartingSync() {
        var app=new SpringApplication(QuestDataApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.addInitializers(c->c.addBeanFactoryPostProcessor(f->((BeanDefinitionRegistry)f)
                .removeBeanDefinition("commandLineRunner")));
        try(var context=app.run()) {
            var owner=context.getBean(IndexMembershipJobService.class);
            assertEquals("index_member",owner.datasetId());
            assertEquals(IndexMembershipJobPlan.definition(),context.getBean(SyncJobRegistry.class)
                    .require("data.index_member",1));
            assertEquals(IndexMembershipDataset.DEFINITION,context.getBean(DatasetRegistry.class)
                    .require("index_member").definition());
        }
    }
}
