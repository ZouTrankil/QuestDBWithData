package com.zoutrankil.data.financial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.zoutrankil.data.financial.application.FinancialDailySync;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

class FinancialSchedulingAdmissionTest {
    @Test
    void enablingTheWebSchedulerCannotCreateATimerInFiniteCli() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new MapPropertySource(
                                    "fixture", Map.of("app.sync.financial.enabled", "true")));
            context.register(FinancialDailySync.class);
            context.refresh();
            assertFalse(context.containsBeanDefinition("financialDailySync"));
            assertEquals(0, context.getBeansOfType(FinancialDailySync.class).size());
        }
    }
}
