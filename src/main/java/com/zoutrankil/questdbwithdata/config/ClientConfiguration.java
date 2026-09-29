package com.zoutrankil.questdbwithdata.config;

import io.netty.channel.ChannelOption;
import io.questdb.client.QuestDB;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.HttpProtocol;
import reactor.netty.resources.ConnectionProvider;
import com.zoutrankil.questdbwithdata.client.HttpObservations;
import com.zoutrankil.questdbwithdata.client.SharedRequestBudget;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ClientConfiguration {
    @Bean(destroyMethod = "close")
    SharedRequestBudget tushareRequestBudget(TushareProperties p) {
        return new SharedRequestBudget(new SharedRequestBudget.Policy(p.getGlobalPerMinute(), p.getEndpointPerMinute(),
                p.getEndpointLimits(), p.getConcurrency(), p.getQueueCapacity(), p.getMaxAttempts(),
                p.getTotalTimeout(), p.getRetryBase(), p.getRetryMax(), p.getRetryableBusinessCodes()),
                java.nio.file.Path.of(System.getProperty("user.home"), ".questdbwithdata", "credential-budgets"));
    }
    @Bean(destroyMethod = "dispose")
    ConnectionProvider tushareConnectionProvider() {
        return ConnectionProvider.builder("tushare-shared").maxConnections(4)
                .pendingAcquireMaxCount(16).pendingAcquireTimeout(Duration.ofSeconds(10))
                .maxIdleTime(Duration.ofSeconds(30)).build();
    }

    @Bean
    WebClient tushareWebClient(WebClient.Builder builder, TushareProperties properties,
                               ConnectionProvider tushareConnectionProvider, HttpObservations observations) {
        HttpClient httpClient = HttpClient.create(tushareConnectionProvider)
                .disableRetry(true)
                .followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .responseTimeout(properties.getResponseTimeout())
                .doOnResponse((response, connection) -> observations.record(
                        connection.channel() instanceof io.netty.handler.codec.http2.Http2StreamChannel
                                ? "HTTP/2.0" : response.version().text(),
                        (connection.channel().parent() == null ? connection.channel() : connection.channel().parent())
                                .id().asLongText(), response.status().code()));
        if ("https".equalsIgnoreCase(properties.getApiUrl().getScheme())) {
            httpClient = httpClient.protocol(HttpProtocol.H2, HttpProtocol.HTTP11).secure();
        } else {
            httpClient = httpClient.protocol(HttpProtocol.HTTP11);
        }
        return builder
                .baseUrl(properties.getApiUrl().toString())
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean(destroyMethod = "close")
    @Lazy
    QuestDB questDbClient(QuestDbProperties properties) {
        return QuestDB.connect(properties.qwpConfig());
    }
}
