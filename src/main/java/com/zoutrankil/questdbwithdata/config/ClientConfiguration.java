package com.zoutrankil.questdbwithdata.config;

import io.netty.channel.ChannelOption;
import io.questdb.client.QuestDB;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration(proxyBeanMethods = false)
public class ClientConfiguration {
    @Bean
    WebClient tushareWebClient(WebClient.Builder builder, TushareProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .responseTimeout(properties.getResponseTimeout());
        return builder
                .baseUrl(properties.getApiUrl().toString())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean(destroyMethod = "close")
    @Lazy
    QuestDB questDbClient(QuestDbProperties properties) {
        return QuestDB.connect(properties.qwpConfig());
    }
}
