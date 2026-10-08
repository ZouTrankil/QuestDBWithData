package com.zoutrankil.batch;

import org.quartz.impl.jdbcjobstore.StdJDBCDelegate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;

/** SQLite JDBC exposes BLOB values as byte arrays and does not implement ResultSet.getBlob(). */
public final class SqliteQuartzDelegate extends StdJDBCDelegate {
    @Override
    protected Object getJobDataFromBlob(ResultSet rs, String column) throws IOException, SQLException {
        byte[] bytes=rs.getBytes(column);
        return bytes==null ? null : new ByteArrayInputStream(bytes);
    }
}
