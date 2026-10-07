package com.zoutrankil.data.repository;

import com.zoutrankil.data.stock.domain.StockDetailState;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class StaticTargetIdentityTest {
    @Test void jdbcBridgeAndCompatibilityEntryPointsUseTheConnectedEndpoint() throws Exception {
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn("jdbc:postgresql://SERVER-A/qdb?password=private-fixture");
        var jdbc = new JdbcTemplate(dataSource);
        String expected = "static-v2-9f134c9496426b7adf775a1741efc25c4cdc7f43baa3f9bfba95a639caaa547b";

        assertEquals(expected, StaticTargetIdentity.identify(jdbc, "stock_detail_info", 0, null));
        assertEquals(expected, com.zoutrankil.data.service.StaticTargetIdentity.identify(
                jdbc, "stock_detail_info", 0, null));
        assertEquals(expected, com.zoutrankil.data.service.StaticTargetIdentity.identify(
                jdbc, "stock_detail_info", new StockDetailState.Identity(0, null)));
        verify(metadata, times(3)).getURL();
        verify(connection, times(3)).close();
    }
}
