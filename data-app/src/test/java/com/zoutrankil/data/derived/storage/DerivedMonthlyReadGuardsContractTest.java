package com.zoutrankil.data.derived.storage;

import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DerivedMonthlyReadGuardsContractTest {
    @Test void guardInternalsRemainPrivateToTheirStoragePackage() {
        for (var guard : List.of(QuestDbEquityStyleReadGuard.class, QuestDbMacroCoreReadGuard.class, QuestDbMacroCoreViewReadGuard.class)) {
            assertFalse(Modifier.isPublic(guard.getModifiers()));
            for (var method : guard.getDeclaredMethods()) assertFalse(Modifier.isPublic(method.getModifiers()));
        }
    }
    @Test void sharedReaderEntryPointsHaveExactlyTheNineOriginalOperations() {
        assertEquals(9, DerivedMonthlyReadGuards.class.getDeclaredMethods().length);
        for (var method : DerivedMonthlyReadGuards.class.getDeclaredMethods()) {
            assertTrue(Modifier.isPublic(method.getModifiers()));
            assertTrue(Modifier.isStatic(method.getModifiers()));
        }
        assertEquals(0, DerivedMonthlyReadGuards.class.getDeclaredFields().length);
    }
}
