package com.zoutrankil.data.derived.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

class DerivedMonthlyReadGuardsContractTest {
    @Test
    void guardInternalsRemainPrivateToTheirStoragePackage() {
        for (var guard :
                List.of(QuestDbEquityStyleReadGuard.class, QuestDbMacroCoreReadGuard.class)) {
            assertFalse(Modifier.isPublic(guard.getModifiers()));
            for (var method : guard.getDeclaredMethods())
                assertFalse(Modifier.isPublic(method.getModifiers()));
        }
    }

    @Test
    void sharedReaderEntryPointsHaveOnlyCanonicalProductOperations() {
        assertEquals(6, DerivedMonthlyReadGuards.class.getDeclaredMethods().length);
        for (var method : DerivedMonthlyReadGuards.class.getDeclaredMethods()) {
            assertTrue(Modifier.isPublic(method.getModifiers()));
            assertTrue(Modifier.isStatic(method.getModifiers()));
        }
        assertEquals(0, DerivedMonthlyReadGuards.class.getDeclaredFields().length);
    }
}
