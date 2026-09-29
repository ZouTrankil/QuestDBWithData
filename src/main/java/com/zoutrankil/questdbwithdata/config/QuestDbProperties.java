package com.zoutrankil.questdbwithdata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.questdb")
public class QuestDbProperties {
    private String host = "127.0.0.1";
    private int pgPort = 8812;
    private int qwpPort = 9000;
    private String username = "admin";
    private String password;
    private String database = "qdb";
    private Duration visibilityTimeout = Duration.ofSeconds(10);
    private Duration pollInterval = Duration.ofMillis(100);
    private int writeBatchRows = 250;
    private int writeBatchBytes = 1024 * 1024;
    private int writeMaxBatches = 100;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPgPort() { return pgPort; }
    public void setPgPort(int pgPort) { this.pgPort = pgPort; }
    public int getQwpPort() { return qwpPort; }
    public void setQwpPort(int qwpPort) { this.qwpPort = qwpPort; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public Duration getVisibilityTimeout() { return visibilityTimeout; }
    public void setVisibilityTimeout(Duration visibilityTimeout) {
        this.visibilityTimeout = visibilityTimeout;
    }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public int getWriteBatchRows() { return writeBatchRows; }
    public void setWriteBatchRows(int writeBatchRows) { this.writeBatchRows = writeBatchRows; }
    public int getWriteBatchBytes() { return writeBatchBytes; }
    public void setWriteBatchBytes(int writeBatchBytes) {
        if (writeBatchBytes < 4096 || writeBatchBytes > 32 * 1024 * 1024) {
            throw new IllegalArgumentException("QWP write byte budget must be 4 KiB..32 MiB");
        }
        this.writeBatchBytes = writeBatchBytes;
    }
    public int getWriteMaxBatches() { return writeMaxBatches; }
    public void setWriteMaxBatches(int writeMaxBatches) { this.writeMaxBatches = writeMaxBatches; }

    public String qwpConfig() {
        return "ws::addr=" + host + ":" + qwpPort
                + ";username=" + username + ";password=" + password
                + ";auto_flush_rows=10000;auto_flush_interval=60000;auto_flush_bytes="
                + (writeBatchBytes / 2) + ";";
    }
}
