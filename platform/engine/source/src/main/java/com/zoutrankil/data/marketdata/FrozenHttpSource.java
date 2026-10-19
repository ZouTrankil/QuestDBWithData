package com.zoutrankil.data.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** 外部来源只做受预算约束的有界读取，完整响应先落不可变文件，不直接访问业务数据库。 */
public final class FrozenHttpSource implements AutoCloseable {
    public record Response(Path path,String sha256,Instant observedAt,String sourceUrl,int bytes) {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Path root;
    private final MarketRequestBudget budget;
    private final HttpClient client;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("market-source-deadline").factory());

    public FrozenHttpSource(Path root,MarketRequestBudget budget,String proxyUrl) {
        this.root=root.toAbsolutePath().normalize();this.budget=Objects.requireNonNull(budget);
        var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL);
        if(proxyUrl!=null&&!proxyUrl.isBlank()) {var proxy=URI.create(proxyUrl);if(proxy.getUserInfo()!=null)throw new IllegalArgumentException("代理凭据须单独配置");builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(),proxy.getPort())));}
        client=builder.build();
    }
    public Response get(String provider,String account,String symbol,URI uri,Map<String,String> headers,int maximumBytes)throws Exception {
        return get(provider,account,symbol,uri,headers,maximumBytes,null);
    }
    /** 个别公开接口需要固定 HTTP 版本；预算、冻结和截止时间仍沿用同一流程。 */
    public Response get(String provider,String account,String symbol,URI uri,Map<String,String> headers,int maximumBytes,HttpClient.Version version)throws Exception {
        if(!Set.of("https","http").contains(uri.getScheme())||uri.getUserInfo()!=null||maximumBytes<1||maximumBytes>64*1024*1024)
            throw new IllegalArgumentException("来源必须是无明文账号的 HTTP(S) 地址，单响应不超过 64 MiB");
        if(!provider.matches("[a-z][a-z0-9_-]{0,63}"))throw new IllegalArgumentException("来源标识符非法");
        String id=budget.reserve(provider,account,symbol,maximumBytes+1);var started=Instant.now();
        if(!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("来源预算返回了非法请求标识");
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("User-Agent","QuestDBWithData/market-data").GET();
        if(version!=null)request.version(version);
        headers.forEach(request::setHeader);
        try {
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try(var input=response.body()) {
                var deadline=timer.schedule(()->{try{input.close();}catch(Exception ignored){}},60,TimeUnit.SECONDS);
                try{bytes=input.readNBytes(maximumBytes+1);}finally{deadline.cancel(false);}
            }
            Instant observed=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Instant retry=retryAt(response.headers().firstValue("Retry-After").orElse(""),observed);
            budget.complete(id,response.statusCode(),bytes.length,retry);
            if(response.statusCode()!=200)throw new SourceStatusException(response.statusCode(),redacted(uri),retry);
            if(bytes.length>maximumBytes)throw new IllegalStateException("来源响应超过冻结预算："+provider);
            String sha=sha(bytes);Path folder=root.resolve("raw").resolve(provider);Files.createDirectories(folder);
            Path target=folder.resolve(id+".response"),pending=folder.resolve(id+".pending");
            Files.write(pending,bytes,StandardOpenOption.CREATE_NEW);
            try(var file=FileChannel.open(pending,StandardOpenOption.WRITE)){file.force(true);}
            Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE);
            Files.write(folder.resolve(id+".json"),JSON.writeValueAsBytes(Map.of("requestId",id,"provider",provider,"symbol",Objects.toString(symbol,""),
                    "sourceUrl",redacted(uri),"startedAt",started.toString(),"observedAt",observed.toString(),"bytes",bytes.length,"sha256",sha)),StandardOpenOption.CREATE_NEW);
            return new Response(target,sha,observed,redacted(uri),bytes.length);
        } catch(SourceStatusException|IllegalStateException e){throw e;}
        catch(Exception e) {budget.complete(id,0,0,null);throw new java.io.IOException("来源请求未完成："+provider+" ("+e.getClass().getSimpleName()+")");}
    }
    public static final class SourceStatusException extends java.io.IOException {
        public final int status;public final Instant retryAfter;
        SourceStatusException(int status,String source,Instant retry){super("来源 HTTP "+status+"："+source);this.status=status;retryAfter=retry;}
    }
    public static String redacted(URI uri) {
        String query=uri.getRawQuery();if(query==null)return uri.toString();
        var fields=new ArrayList<String>();
        for(String item:query.split("&")) {
            int at=item.indexOf('=');String key=at<0?item:item.substring(0,at);String name=URLDecoder.decode(key,StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            fields.add(name.matches(".*(token|api.?key|password|authorization|secret).*")?key+"=REDACTED":item);
        }
        return uri.getScheme()+"://"+uri.getRawAuthority()+uri.getRawPath()+"?"+String.join("&",fields);
    }
    private static Instant retryAt(String value,Instant now) {
        try{return now.plusSeconds(Math.max(0,Long.parseLong(value)));}catch(Exception ignored){}
        try{return ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(Exception ignored){return null;}
    }
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Override public void close(){timer.shutdownNow();client.close();}
}
