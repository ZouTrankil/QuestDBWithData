package com.zoutrankil.questdbwithdata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.tushare")
public class TushareProperties {
    private String token;
    private URI apiUrl = URI.create("https://api.tushare.pro");
    private Duration connectTimeout = Duration.ofSeconds(15);
    private Duration responseTimeout = Duration.ofSeconds(45);
    private Duration requestTimeout = Duration.ofSeconds(50);

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public URI getApiUrl() { return apiUrl; }
    public void setApiUrl(URI apiUrl) { this.apiUrl = apiUrl; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getResponseTimeout() { return responseTimeout; }
    public void setResponseTimeout(Duration responseTimeout) { this.responseTimeout = responseTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
}
