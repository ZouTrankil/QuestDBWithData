package com.zoutrankil.data.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.WebFilter;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebApiProperties.class)
public class WebApiConfiguration {
    @Bean
    CorsWebFilter webCorsFilter(WebApiProperties properties) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(properties.allowedOrigins().split(",")));
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        cors.setAllowCredentials(!properties.allowedOrigins().equals("*"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return new CorsWebFilter(source);
    }

    @Bean
    WebFilter webApiTokenFilter(WebApiProperties properties) {
        return (exchange, chain) -> {
            String path = exchange.getRequest().getPath().value();
            if (!path.startsWith("/api/v1/") || path.equals("/api/v1/health")) {
                return chain.filter(exchange);
            }
            String expected = "Bearer " + properties.apiToken();
            String supplied =
                    exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            // 没有本机凭据时也拒绝请求，不能把空 token 当成关闭鉴权。
            if (properties.apiToken().isEmpty()
                    || supplied == null
                    || !MessageDigest.isEqual(
                            expected.getBytes(StandardCharsets.UTF_8),
                            supplied.getBytes(StandardCharsets.UTF_8))) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                exchange.getResponse()
                        .getHeaders()
                        .set(HttpHeaders.CONTENT_TYPE, "application/json");
                return exchange.getResponse()
                        .writeWith(
                                Mono.just(
                                        exchange.getResponse()
                                                .bufferFactory()
                                                .wrap(
                                                        "{\"error\":\"unauthorized\"}"
                                                                .getBytes(
                                                                        StandardCharsets.UTF_8))));
            }
            return chain.filter(exchange);
        };
    }
}
