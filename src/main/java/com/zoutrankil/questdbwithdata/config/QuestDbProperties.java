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

    public String qwpConfig() {
        return "ws::addr=" + host + ":" + qwpPort
                + ";username=" + username + ";password=" + password + ";";
    }
}
