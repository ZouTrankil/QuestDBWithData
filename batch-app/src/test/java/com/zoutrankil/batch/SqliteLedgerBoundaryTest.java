package com.zoutrankil.batch;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.support.JdbcAccessor;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteLedgerBoundaryTest {
    @Test void ledgerDoesNotExposeSqlExecutionOrDataSourceHandles() {
        var leaks = Arrays.stream(SqliteLedger.class.getDeclaredMethods())
                .filter(method -> !Modifier.isPrivate(method.getModifiers()))
                .filter(method -> exposesStorageHandle(method.getReturnType()))
                .map(method -> method.getName() + " -> " + method.getReturnType().getName())
                .sorted()
                .toList();
        assertTrue(leaks.isEmpty(), () -> "Ledger callers must use its storage operations: " + leaks);
    }

    private static boolean exposesStorageHandle(Class<?> type) {
        return DataSource.class.isAssignableFrom(type)
                || JdbcOperations.class.isAssignableFrom(type)
                || NamedParameterJdbcOperations.class.isAssignableFrom(type)
                || JdbcAccessor.class.isAssignableFrom(type);
    }
}
