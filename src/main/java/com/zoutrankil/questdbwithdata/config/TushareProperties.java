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
    private int globalPerMinute = 20;
    private int endpointPerMinute = 10;
    private java.util.Map<String, Integer> endpointLimits = java.util.Map.of();
    private int concurrency = 2;
    private int queueCapacity = 16;
    private int maxAttempts = 3;
    private Duration totalTimeout = Duration.ofMinutes(2);
    private Duration retryBase = Duration.ofSeconds(1);
    private Duration retryMax = Duration.ofSeconds(10);
    private java.util.Set<Integer> retryableBusinessCodes = java.util.Set.of();

    public int getGlobalPerMinute() { return globalPerMinute; }
    public void setGlobalPerMinute(int value) { globalPerMinute = value; }
    public int getEndpointPerMinute() { return endpointPerMinute; }
    public void setEndpointPerMinute(int value) { endpointPerMinute = value; }
    public java.util.Map<String, Integer> getEndpointLimits() { return endpointLimits; }
    /** Dataset-specific ceilings must not be raised by a broader account/default setting. */
    public java.util.Map<String,Integer> effectiveEndpointLimits() {
        var limits = new java.util.HashMap<>(endpointLimits);
        limits.put("trade_cal",Math.min(20,Math.min(endpointPerMinute,limits.getOrDefault("trade_cal",endpointPerMinute))));
        limits.put("stock_basic",Math.min(50,Math.min(endpointPerMinute,limits.getOrDefault("stock_basic",endpointPerMinute))));
        limits.put("ths_index",Math.min(200,Math.min(endpointPerMinute,limits.getOrDefault("ths_index",endpointPerMinute))));
        limits.put("ths_member",Math.min(200,Math.min(endpointPerMinute,limits.getOrDefault("ths_member",endpointPerMinute))));
        limits.put("index_classify",Math.min(100,Math.min(endpointPerMinute,limits.getOrDefault("index_classify",endpointPerMinute))));
        limits.put("index_member_all",Math.min(5000,Math.min(endpointPerMinute,limits.getOrDefault("index_member_all",endpointPerMinute))));
        return java.util.Map.copyOf(limits);
    }
    public void setEndpointLimits(java.util.Map<String, Integer> value) { endpointLimits = value; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int value) { concurrency = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = value; }
    public Duration getTotalTimeout() { return totalTimeout; }
    public void setTotalTimeout(Duration value) { totalTimeout = value; }
    public Duration getRetryBase() { return retryBase; }
    public void setRetryBase(Duration value) { retryBase = value; }
    public Duration getRetryMax() { return retryMax; }
    public void setRetryMax(Duration value) { retryMax = value; }
    public java.util.Set<Integer> getRetryableBusinessCodes() { return retryableBusinessCodes; }
    public void setRetryableBusinessCodes(java.util.Set<Integer> value) { retryableBusinessCodes = value; }

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
