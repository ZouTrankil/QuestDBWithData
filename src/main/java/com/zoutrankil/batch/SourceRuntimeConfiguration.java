package com.zoutrankil.batch;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import com.zoutrankil.questdbwithdata.client.*;
import com.zoutrankil.questdbwithdata.config.TushareProperties;
import com.zoutrankil.questdbwithdata.service.TusharePageService;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.transaction.PlatformTransactionManager;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.InputStream;

@Configuration(proxyBeanMethods=false)
public class SourceRuntimeConfiguration {
    @Bean SourceCollector sourceCollector(Environment env) { return new SourceCollector(Path.of(env.getRequiredProperty("jdb.archive-root"))); }
    @Bean TushareProperties sourceTushareProperties(Environment env) {
        var properties=new TushareProperties(); properties.setToken(env.getProperty("jdb.tushare-token","")); return properties;
    }
    @Bean(destroyMethod="close") SharedRequestBudget sourceRequestBudget(TushareProperties properties) {
        return new SharedRequestBudget(new SharedRequestBudget.Policy(properties.getGlobalPerMinute(),properties.getEndpointPerMinute(),
                properties.getEndpointLimits(),properties.getConcurrency(),properties.getQueueCapacity(),properties.getMaxAttempts(),
                properties.getTotalTimeout(),properties.getRetryBase(),properties.getRetryMax(),properties.getRetryableBusinessCodes()),
                Path.of(System.getProperty("user.home"),".questdbwithdata","credential-budgets"));
    }
    @Bean(destroyMethod="close") SharedRequestBudget chinabondRequestBudget(TushareProperties properties) {
        return new SharedRequestBudget(new SharedRequestBudget.Policy(properties.getGlobalPerMinute(),properties.getEndpointPerMinute(),
                properties.getEndpointLimits(),properties.getConcurrency(),properties.getQueueCapacity(),properties.getMaxAttempts(),
                properties.getTotalTimeout(),properties.getRetryBase(),properties.getRetryMax(),properties.getRetryableBusinessCodes()),
                Path.of(System.getProperty("user.home"),".questdbwithdata","public-source-budgets"));
    }
    @Bean TusharePageService sourcePages(TushareProperties properties,@Qualifier("sourceRequestBudget") SharedRequestBudget budget) {
        var http=HttpClient.create().disableRetry(true).followRedirect(false)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .responseTimeout(properties.getResponseTimeout());
        var client=WebClient.builder().baseUrl(properties.getApiUrl().toString())
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8*1024*1024)).build();
        return new TusharePageService(new TushareClient(client,properties,budget));
    }
    @Bean ChinabondYieldSource chinabondYieldSource(@Qualifier("chinabondRequestBudget") SharedRequestBudget budget,TushareProperties properties) {
        var ssl=chinaBondSslContext();
        var http=HttpClient.create().disableRetry(true).followRedirect(false)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS,Math.toIntExact(properties.getConnectTimeout().toMillis()))
                .responseTimeout(properties.getResponseTimeout()).secure(spec -> spec.sslContext(ssl));
        var web=WebClient.builder().baseUrl("https://yield.chinabond.com.cn")
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8*1024*1024)).build();
        return new ChinabondYieldSource(web,budget,properties);
    }
    private static io.netty.handler.ssl.SslContext chinaBondSslContext() {
        try {
            var defaults=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            defaults.init((KeyStore)null);
            X509TrustManager system=java.util.Arrays.stream(defaults.getTrustManagers()).filter(X509TrustManager.class::isInstance)
                    .map(X509TrustManager.class::cast).findFirst().orElseThrow();
            X509Certificate cfca;
            try(InputStream cert=SourceRuntimeConfiguration.class.getResourceAsStream("/trust/cfca-ev-root.pem")) {
                if(cert==null) throw new IllegalStateException("Bundled CFCA root certificate is missing");
                cfca=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(cert);
            }
            String fingerprint=java.util.HexFormat.ofDelimiter(":").withUpperCase().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(cfca.getEncoded()));
            if(!fingerprint.equals("5C:C3:D7:8E:4E:1D:5E:45:54:7A:04:E6:87:3E:64:F9:0C:F9:53:6D:1C:CC:2E:F8:00:F3:55:C4:C5:FD:70:FD"))
                throw new IllegalStateException("Bundled CFCA root fingerprint mismatch");
            var extraStore=KeyStore.getInstance(KeyStore.getDefaultType());extraStore.load(null,null);extraStore.setCertificateEntry("cfca-ev-root",cfca);
            var extraFactory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());extraFactory.init(extraStore);
            X509TrustManager extra=java.util.Arrays.stream(extraFactory.getTrustManagers()).filter(X509TrustManager.class::isInstance)
                    .map(X509TrustManager.class::cast).findFirst().orElseThrow();
            X509TrustManager combined=new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                    var issuers=new java.util.LinkedHashSet<X509Certificate>();java.util.Collections.addAll(issuers,system.getAcceptedIssuers());
                    java.util.Collections.addAll(issuers,extra.getAcceptedIssuers());return issuers.toArray(X509Certificate[]::new);
                }
                public void checkClientTrusted(X509Certificate[] chain,String authType) throws java.security.cert.CertificateException { system.checkClientTrusted(chain,authType); }
                public void checkServerTrusted(X509Certificate[] chain,String authType) throws java.security.cert.CertificateException {
                    try { system.checkServerTrusted(chain,authType); } catch(java.security.cert.CertificateException standardFailure) {
                        try { extra.checkServerTrusted(chain,authType); } catch(java.security.cert.CertificateException extraFailure) {
                            standardFailure.addSuppressed(extraFailure);throw standardFailure;
                        }
                    }
                }
            };
            return SslContextBuilder.forClient().trustManager(combined).build();
        } catch(Exception failure) { throw new IllegalStateException("Unable to configure verified ChinaBond TLS trust",failure); }
    }
    @Bean NativeSourceService nativeSources(SqliteLedger ledger,SourceCollector collector,TusharePageService pages,ChinabondYieldSource chinabond) {
        return new NativeSourceService(ledger,collector,pages,chinabond);
    }
    @Bean SourceProductRunner sourceRunner(SqliteLedger ledger,SourceCollector collector,Environment env) {
        String endpoint=env.getProperty("jdb.questdb-http-url","");
        return new SourceProductRunner(ledger,collector,Path.of(env.getRequiredProperty("jdb.archive-root")),
                endpoint.isBlank()?null:URI.create(endpoint),env.getProperty("jdb.questdb-http-authorization",""));
    }
    @Bean Job dailySource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"daily");
    }
    @Bean Job dailyBasicSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"daily_basic");
    }
    @Bean Job etfDailySource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"etf_daily");
    }
    @Bean Job stkLimitSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"stk_limit");
    }
    @Bean Job etfAdjSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"etf_adj");
    }
    @Bean Job moneyflowSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"moneyflow");
    }
    @Bean Job etfFactorSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"etf_factor");
    }
    @Bean Job marginDetailSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"margin_detail");
    }
    @Bean Job moneyflowHsgtSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"moneyflow_hsgt");
    }
    @Bean Job stkSuspendSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"stk_suspend");
    }
    @Bean Job etfPortfolioSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"etf_portfolio");
    }
    @Bean Job stkFactorSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"stk_factor");
    }
    @Bean Job stkStDailySource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"stk_st_daily");
    }
    @Bean Job cnBondYieldCurveSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"cn_bond_yield_curve");
    }
    @Bean Job indexDailyMarketSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"index_daily_market");
    }
    @Bean Job indexDailyBasicSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"index_daily_basic");
    }
    @Bean Job cyqPerfSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"cyq_perf");
    }
    @Bean Job exchangeCalendarSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"exchange_calendar");
    }
    @Bean Job finaMainbzSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"fina_mainbz");
    }
    @Bean Job finaAuditSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"fina_audit");
    }
    @Bean Job dividendSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"dividend");
    }
    @Bean Job shareFloatSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"share_float");
    }
    @Bean Job shiborSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"shibor");
    }
    @Bean Job shiborLprSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"shibor_lpr");
    }
    @Bean Job hiborSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"hibor");
    }
    @Bean Job cnCpiSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"cn_cpi");
    }
    @Bean Job cnPpiSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"cn_ppi");
    }
    @Bean Job cnPmiSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"cn_pmi");
    }
    @Bean Job cnMSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"cn_m");
    }
    @Bean Job cnGdpSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"cn_gdp");
    }
    @Bean Job futDailySource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"fut_daily");
    }
    @Bean Job futSettleSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"fut_settle");
    }
    @Bean Job futMappingSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"fut_mapping");
    }
    @Bean Job ftLimitSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"ft_limit");
    }
    @Bean Job futHoldingSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"fut_holding");
    }
    @Bean Job futBasicSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"fut_basic");
    }
    @Bean Job etfBasicSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"etf_basic");
    }
    @Bean Job disclosureDateSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"disclosure_date");
    }
    @Bean Job thsIndexSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"ths_index");
    }
    @Bean Job etfShareSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"etf_share");
    }
    @Bean Job usTbrSource(JobRepository repository,PlatformTransactionManager transactionManager,SqliteLedger ledger,SourceProductRunner runner) {
        return BatchJobs.source(repository,transactionManager,ledger,runner,"us_tbr");
    }
}
