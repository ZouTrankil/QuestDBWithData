package com.zoutrankil.batch;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.quartz.*;

/** Loopback management API. Read routes have no producer side effects; all routes require a token. */
public final class ManagementServer implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final byte[] token;
    private final SqliteLedger ledger;
    private final LaunchService launches;
    private final Scheduler scheduler;
    private final NativeSourceService sources;
    private final L2ArchiveAdmission l2Archives;
    private final org.springframework.batch.core.launch.JobOperator jobOperator;
    private final org.springframework.batch.core.job.Job l2ArchiveJob;
    private final BaiduL2Subscription baiduL2Subscription;
    private final BaiduL2ArchiveDownloader baiduL2ArchiveDownloader;
    public ManagementServer(int port,String token,SqliteLedger ledger,LaunchService launches,Scheduler scheduler) throws Exception {
        this(port,token,ledger,launches,scheduler,null);
    }
    public ManagementServer(int port,String token,SqliteLedger ledger,LaunchService launches,Scheduler scheduler,NativeSourceService sources) throws Exception {
        this(port,token,ledger,launches,scheduler,sources,null);
    }
    public ManagementServer(int port,String token,SqliteLedger ledger,LaunchService launches,Scheduler scheduler,NativeSourceService sources,L2ArchiveAdmission l2Archives) throws Exception {
        this(port,token,ledger,launches,scheduler,sources,l2Archives,null,null);
    }
    public ManagementServer(int port,String token,SqliteLedger ledger,LaunchService launches,Scheduler scheduler,NativeSourceService sources,L2ArchiveAdmission l2Archives,
                            org.springframework.batch.core.launch.JobOperator jobOperator,org.springframework.batch.core.job.Job l2ArchiveJob) throws Exception {
        this(port,token,ledger,launches,scheduler,sources,l2Archives,jobOperator,l2ArchiveJob,null);
    }
    public ManagementServer(int port,String token,SqliteLedger ledger,LaunchService launches,Scheduler scheduler,NativeSourceService sources,L2ArchiveAdmission l2Archives,
                            org.springframework.batch.core.launch.JobOperator jobOperator,org.springframework.batch.core.job.Job l2ArchiveJob,
                            BaiduL2Subscription baiduL2Subscription) throws Exception {
        this(port,token,ledger,launches,scheduler,sources,l2Archives,jobOperator,l2ArchiveJob,baiduL2Subscription,null);
    }
    public ManagementServer(int port,String token,SqliteLedger ledger,LaunchService launches,Scheduler scheduler,NativeSourceService sources,L2ArchiveAdmission l2Archives,
                            org.springframework.batch.core.launch.JobOperator jobOperator,org.springframework.batch.core.job.Job l2ArchiveJob,
                            BaiduL2Subscription baiduL2Subscription,BaiduL2ArchiveDownloader baiduL2ArchiveDownloader) throws Exception {
        this.sources=sources;
        this.l2Archives=l2Archives;
        this.jobOperator=jobOperator; this.l2ArchiveJob=l2ArchiveJob;
        this.baiduL2Subscription=baiduL2Subscription;
        this.baiduL2ArchiveDownloader=baiduL2ArchiveDownloader;
        if (token==null || token.length()<24) throw new IllegalArgumentException("JDB_API_TOKEN must have at least 24 characters");
        this.token=("Bearer "+token).getBytes(StandardCharsets.UTF_8); this.ledger=ledger; this.launches=launches; this.scheduler=scheduler;
        server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),port),16);
        workers=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers); server.createContext("/v1/",this::handle);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            String supplied=exchange.getRequestHeaders().getFirst("Authorization");
            if (supplied==null || !MessageDigest.isEqual(token,supplied.getBytes(StandardCharsets.UTF_8))) {
                respond(exchange,401,Map.of("error","unauthorized")); return;
            }
            try {
                String path=exchange.getRequestURI().getPath(); String method=exchange.getRequestMethod();
                if ("GET".equals(method) && path.equals("/v1/health")) {
                    ledger.ping();
                    respond(exchange,200,Map.of("status","UP","metadataStore","SQLite","schedulerStarted",scheduler.isStarted(),
                            "schedulerStandby",scheduler.isInStandbyMode(),"productionEnabled",false));
                } else if ("GET".equals(method) && path.equals("/v1/metrics")) {
                    var metrics=new LinkedHashMap<String,Object>(ledger.metrics());
                    metrics.put("registeredJobs",ScheduleCatalog.SCHEDULES.keySet().stream().sorted().toList());
                    metrics.put("schedulerStarted",scheduler.isStarted());metrics.put("productionEnabled",false);
                    respond(exchange,200,metrics);
                } else if ("GET".equals(method) && path.equals("/v1/coverage/monthly")) {
                    respond(exchange,200,ledger.monthlyCoverage());
                } else if ("GET".equals(method) && path.equals("/v1/coverage/quarterly")) {
                    respond(exchange,200,ledger.quarterlyCoverage());
                } else if ("GET".equals(method) && path.equals("/v1/metrics/prometheus")) {
                    respondText(exchange,200,"text/plain; version=0.0.4; charset=utf-8",prometheusMetrics());
                } else if ("POST".equals(method) && path.equals("/v1/backfills/plan")) {
                    byte[] body=exchange.getRequestBody().readNBytes(4097);
                    if(body.length>4096) { respond(exchange,413,Map.of("error","request-too-large")); return; }
                    BackfillPlanRequest request=Json.read(new String(body,StandardCharsets.UTF_8),BackfillPlanRequest.class);
                    if(request.job()==null || !request.job().startsWith("source_")) throw new IllegalArgumentException("Only registered native source jobs can be planned");
                    String dataset=request.job().substring("source_".length());
                    if(!SourceContract.SUPPORTED.contains(dataset)) throw new IllegalArgumentException("Unregistered source job");
                    if(request.start()==null || request.end()==null || request.maxPartitions()==null) throw new IllegalArgumentException("start, end and maxPartitions are required");
                    boolean monthly=SourceContract.MONTHLY_AGGREGATES.contains(dataset);
                    boolean quarterly=SourceContract.QUARTERLY_AGGREGATES.contains(dataset);
                    TradingCalendar calendar=(TradingCalendar)scheduler.getContext().get("calendar");
                    if(monthly) {
                        if(!RecoveryPolicy.MONTHLY_PERIOD_VERSION.equals(request.calendarVersion()))
                            throw new IllegalArgumentException("Monthly ranges require calendarVersion="+RecoveryPolicy.MONTHLY_PERIOD_VERSION);
                    } else if(quarterly) {
                        if(!RecoveryPolicy.QUARTERLY_PERIOD_VERSION.equals(request.calendarVersion()))
                            throw new IllegalArgumentException("Quarterly ranges require calendarVersion="+RecoveryPolicy.QUARTERLY_PERIOD_VERSION);
                    } else if(calendar==null || request.calendarVersion()==null || !request.calendarVersion().equals(calendar.version()))
                        throw new IllegalArgumentException("Matching versioned trading calendar required for backfill planning");
                    var start=java.time.LocalDate.parse(request.start());var end=java.time.LocalDate.parse(request.end());
                    int partitionLimit=monthly||quarterly?Math.min(request.maxPartitions(),Math.min(2000,SourceContract.load(dataset).maxRows())):request.maxPartitions();
                    var dates=monthly?RecoveryPolicy.monthlyBackfill(start,end,partitionLimit):quarterly?RecoveryPolicy.quarterlyBackfill(start,end,partitionLimit):RecoveryPolicy.backfill(start,end,partitionLimit,calendar);
                    respond(exchange,200,Map.ofEntries(Map.entry("job",request.job()),
                            Map.entry("calendarVersion",monthly?RecoveryPolicy.MONTHLY_PERIOD_VERSION:quarterly?RecoveryPolicy.QUARTERLY_PERIOD_VERSION:calendar.version()),
                            Map.entry("frequency",monthly?"MONTH":quarterly?"QUARTER":"TRADING_DAY"),Map.entry("start",request.start()),
                            Map.entry("end",request.end()),Map.entry("maxPartitions",partitionLimit),Map.entry("requestedMaxPartitions",request.maxPartitions()),
                            Map.entry("plannedPartitions",dates.size()),Map.entry("plannedDates",dates),Map.entry("dryRun",true),Map.entry("enqueued",false)));
                } else if ("GET".equals(method) && path.equals("/v1/tasks")) {
                    respond(exchange,200,Map.of("jobs",List.of("post_close","pre_open_acceptance","l2_archive_integrity","source_daily","source_daily_basic","source_etf_daily","source_stk_limit","source_etf_adj","source_moneyflow","source_etf_factor","source_margin_detail","source_moneyflow_hsgt","source_stk_suspend","source_etf_portfolio","source_stk_factor","source_stk_st_daily","source_cn_bond_yield_curve","source_cyq_perf","source_index_daily_market","source_index_daily_basic","source_exchange_calendar","source_fina_mainbz","source_fina_audit","source_dividend","source_share_float","source_shibor","source_shibor_lpr","source_hibor","source_cn_cpi","source_cn_ppi","source_cn_pmi","source_cn_m","source_cn_gdp","source_fut_daily","source_fut_settle","source_fut_mapping","source_ft_limit","source_fut_holding","source_fut_basic","source_etf_basic","source_disclosure_date","source_ths_index","source_etf_share","source_us_tbr"),"productionEnabled",false));
                } else if ("GET".equals(method) && path.equals("/v1/sources")) {
                    respond(exchange,200,SourceContract.SUPPORTED.stream().sorted().map(SourceContract::load).toList());
                } else if ("POST".equals(method) && path.equals("/v1/sources/collect")) {
                    if(sources==null) throw new IllegalArgumentException("Source service not configured");
                    byte[] body=exchange.getRequestBody().readNBytes(1024*1024+1);
                    if(body.length>1024*1024) { respond(exchange,413,Map.of("error","request-too-large")); return; }
                    var request=Json.read(new String(body,StandardCharsets.UTF_8),SourceCollector.Request.class);
                    respond(exchange,200,sources.collect(exchange.getRequestHeaders().getFirst("Idempotency-Key"),request));
                } else if ("POST".equals(method) && path.equals("/v1/l2/archives/inspect")) {
                    if(l2Archives==null || jobOperator==null || l2ArchiveJob==null) throw new IllegalArgumentException("L2 archive service not configured");
                    byte[] body=exchange.getRequestBody().readNBytes(4097);
                    if(body.length>4096) { respond(exchange,413,Map.of("error","request-too-large")); return; }
                    ArchiveInspectionRequest request=Json.read(new String(body,StandardCharsets.UTF_8),ArchiveInspectionRequest.class);
                    if(request.path()==null || request.path().isBlank() || request.path().length()>2048) throw new IllegalArgumentException("Archive path required (maximum 2048 characters)");
                    java.nio.file.Path archivePath=java.nio.file.Path.of(request.path()).toAbsolutePath().normalize();
                    var attrs=java.nio.file.Files.readAttributes(archivePath,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    var parameters=new org.springframework.batch.core.job.parameters.JobParametersBuilder()
                            .addString("archivePath",archivePath.toString()).addLong("archiveSizeBytes",attrs.size())
                            .addLong("archiveModifiedMillis",attrs.lastModifiedTime().toMillis())
                            .addString("inspectionId",UUID.randomUUID().toString()).toJobParameters();
                    org.springframework.batch.core.job.JobExecution execution;
                    try { execution=jobOperator.start(l2ArchiveJob,parameters); }
                    catch(org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException alreadyVerified) {
                        respond(exchange,200,Map.of("job","l2_archive_integrity","status","ALREADY_VERIFIED")); return;
                    }
                    if(execution.getStatus()==org.springframework.batch.core.BatchStatus.COMPLETED) {
                        var evidence=new LinkedHashMap<String,Object>();
                        var context=execution.getExecutionContext();
                        for(String key:List.of("archiveSha256","frozenPath","tradeDate","status","verification","archiveMembers","archiveSymbols","sourceRows","tradeDateMismatches","revisionOfSha256","parseStatus","materializationManifest","parserVersion","materializationReused","dealRows","orderRows","quoteRows","writeStatus","businessInstanceId","writeRows","writeTargets","ingestCertificate"))
                            if(context.containsKey(key)) evidence.put(key,key.equals("materializationReused")?Boolean.parseBoolean(context.getString(key)):context.get(key));
                        respond(exchange,200,Map.of("job","l2_archive_integrity","batchExecutionId",execution.getId(),"result",evidence));
                    } else if(execution.getAllFailureExceptions().stream().anyMatch(ManagementServer::causedByNotStable)) {
                        respond(exchange,409,Map.of("error","archive-not-stable","batchExecutionId",execution.getId()));
                    } else {
                        respond(exchange,422,Map.of("error","archive-invalid","batchExecutionId",execution.getId()));
                    }
                } else if ("POST".equals(method) && path.equals("/v1/l2/subscription/transfer")) {
                    if(baiduL2Subscription==null)throw new IllegalArgumentException("Baidu L2 subscription is disabled or not configured");
                    byte[] body=exchange.getRequestBody().readNBytes(4097);
                    if(body.length>4096){respond(exchange,413,Map.of("error","request-too-large"));return;}
                    var request=Json.read(new String(body,StandardCharsets.UTF_8),BaiduTransferRequest.class);
                    if(request.date()==null||request.dryRun()==null)throw new IllegalArgumentException("date and explicit dryRun are required");
                    java.time.LocalDate date;
                    try { date=java.time.LocalDate.parse(request.date()); }
                    catch(java.time.DateTimeException invalidDate) { throw new IllegalArgumentException("date must be an ISO-8601 calendar date"); }
                    var result=baiduL2Subscription.transfer(date,request.dryRun());
                    ledger.audit(null,"baidu-l2-transfer",result.status()+":"+result.logicalDate()+":dryRun="+request.dryRun());
                    respond(exchange,200,Map.of("result",result));
                } else if ("POST".equals(method) && path.equals("/v1/l2/subscription/download")) {
                    if(baiduL2ArchiveDownloader==null)throw new IllegalArgumentException("Baidu L2 download is disabled or not configured");
                    byte[] body=exchange.getRequestBody().readNBytes(4097);
                    if(body.length>4096){respond(exchange,413,Map.of("error","request-too-large"));return;}
                    var request=Json.read(new String(body,StandardCharsets.UTF_8),BaiduTransferRequest.class);
                    if(request.date()==null||request.dryRun()==null)throw new IllegalArgumentException("date and explicit dryRun are required");
                    java.time.LocalDate date;
                    try { date=java.time.LocalDate.parse(request.date()); }
                    catch(java.time.DateTimeException invalidDate) { throw new IllegalArgumentException("date must be an ISO-8601 calendar date"); }
                    var result=baiduL2ArchiveDownloader.download(date,request.dryRun());
                    ledger.audit(null,"baidu-l2-download",result.status()+":"+result.logicalDate()+":dryRun="+request.dryRun());
                    respond(exchange,200,Map.of("result",result));
                } else if ("GET".equals(method) && path.equals("/v1/runs")) {
                    respond(exchange,200,ledger.recent());
                } else if ("GET".equals(method) && path.equals("/v1/reconciliation")) {
                    respond(exchange,200,ledger.reconciliationQueue());
                } else if ("GET".equals(method) && path.matches("/v1/runs/[a-f0-9]{64}")) {
                    respond(exchange,200,ledger.detail(path.substring("/v1/runs/".length())));
                } else if ("POST".equals(method) && path.equals("/v1/runs")) {
                    byte[] body=exchange.getRequestBody().readNBytes(65537);
                    if (body.length>65536) { respond(exchange,413,Map.of("error","request-too-large")); return; }
                    var request=Json.read(new String(body,StandardCharsets.UTF_8),RunRequest.class);
                    String id=exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    if (!request.requestId().equals(id)) throw new IllegalArgumentException("Idempotency-Key must match requestId");
                    respond(exchange,200,launches.launch(request));
                } else if ("GET".equals(method) && path.equals("/v1/schedules")) {
                    var list=new ArrayList<Map<String,Object>>();
                    for (var key:scheduler.getTriggerKeys(org.quartz.impl.matchers.GroupMatcher.triggerGroupEquals("jdb"))) {
                        var trigger=scheduler.getTrigger(key);
                        var item=new LinkedHashMap<String,Object>(); item.put("id",key.getName());
                        item.put("state",scheduler.getTriggerState(key).name());
                        item.put("nextFireTime",trigger.getNextFireTime()==null?null:trigger.getNextFireTime().toInstant().toString()); list.add(item);
                    }
                    respond(exchange,200,list);
                } else if ("POST".equals(method) && path.matches("/v1/schedules/[a-z_]+/(pause|resume)")) {
                    String[] segments=path.split("/");String task=segments[3];
                    if(!ScheduleCatalog.SCHEDULES.containsKey(task)) { respond(exchange,404,Map.of("error","unknown-schedule"));return; }
                    var key=new JobKey(task,"jdb");
                    if (!scheduler.checkExists(key)) throw new IllegalArgumentException("Schedule not configured");
                    boolean pause=path.endsWith("/pause");
                    if (pause) scheduler.pauseJob(key);
                    else {
                        if (!scheduler.getContext().containsKey("calendar")) throw new IllegalArgumentException("Calendar required before resume");
                        if (!scheduler.isStarted() || scheduler.isInStandbyMode()) throw new IllegalArgumentException("Scheduling disabled by runtime configuration");
                        scheduler.resumeJob(key);
                    }
                    ledger.audit(null,"schedule-control",(pause?"pause-":"resume-")+task);
                    respond(exchange,200,Map.of("paused",pause,"currentExecutionCancelled",false));
                } else { respond(exchange,404,Map.of("error","unknown-route")); }
            } catch (IllegalArgumentException error) {
                respond(exchange,400,Map.of("error",error.getMessage()));
            } catch (L2ArchiveAdmission.NotStableException pending) {
                respond(exchange,409,Map.of("error","archive-not-stable","detail",pending.getMessage()));
            } catch (java.io.IOException invalidArchive) {
                respond(exchange,422,Map.of("error","archive-invalid"));
            } catch (org.springframework.dao.EmptyResultDataAccessException missing) {
                respond(exchange,404,Map.of("error","not-found"));
            } catch (Exception error) {
                // Never return SQL, credentials, connection strings, or raw provider messages.
                respond(exchange,503,Map.of("error","operation-unavailable","type",error.getClass().getSimpleName()));
            }
        }
    }
    public record ArchiveInspectionRequest(String path) {}
    public record BaiduTransferRequest(String date,Boolean dryRun) {}
    public record BackfillPlanRequest(String job,String start,String end,Integer maxPartitions,String calendarVersion) {}
    private String prometheusMetrics() throws SchedulerException {
        var snapshot=ledger.metrics(); var out=new StringBuilder();
        appendStates(out,"jdb_business_instances","Business instances by persisted business state",snapshot.get("businessInstancesByState"));
        appendStates(out,"jdb_source_probes","Native source probes by persisted state",snapshot.get("sourceProbesByState"));
        appendStates(out,"jdb_write_intents","Durable QuestDB write intents by delivery state",snapshot.get("writeIntentsByDelivery"));
        gauge(out,"jdb_metadata_up","SQLite metadata query health",1);
        gauge(out,"jdb_scheduler_started","Whether the Quartz scheduler is started",scheduler.isStarted()?1:0);
        gauge(out,"jdb_scheduler_standby","Whether the Quartz scheduler is in standby",scheduler.isInStandbyMode()?1:0);
        gauge(out,"jdb_production_enabled","Production execution is disabled in this distribution",0);
        gauge(out,"jdb_verified_monthly_observations","Persisted physically verified monthly source observations",
                ((Number)snapshot.get("verifiedMonthlyObservations")).longValue());
        gauge(out,"jdb_verified_quarterly_observations","Persisted physically verified quarterly source observations",
                ((Number)snapshot.get("verifiedQuarterlyObservations")).longValue());
        gauge(out,"jdb_registered_schedules","Registered persistent schedules",ScheduleCatalog.SCHEDULES.size());
        gauge(out,"jdb_management_workers_active","Currently active management request workers",workers.getActiveCount());
        gauge(out,"jdb_management_workers_queued","Management requests waiting for a worker",workers.getQueue().size());
        return out.toString();
    }
    private static void appendStates(StringBuilder out,String metric,String help,Object rows) {
        out.append("# HELP ").append(metric).append(' ').append(help).append('\n')
                .append("# TYPE ").append(metric).append(" gauge\n");
        if(rows instanceof List<?> list) for(Object row:list) if(row instanceof Map<?,?> values) {
            Object state=values.get("state"),count=values.get("count");
            if(state!=null && count instanceof Number number)
                out.append(metric).append("{state=\"").append(prometheusLabel(state.toString())).append("\"} ").append(number.longValue()).append('\n');
        }
    }
    private static void gauge(StringBuilder out,String name,String help,long value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n')
                .append("# TYPE ").append(name).append(" gauge\n")
                .append(name).append(' ').append(value).append('\n');
    }
    private static String prometheusLabel(String value) { return value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n"); }
    private static boolean causedByNotStable(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) if(cause instanceof L2ArchiveAdmission.NotStableException) return true;
        return false;
    }
    private static void respond(HttpExchange exchange,int status,Object value) throws java.io.IOException {
        respondText(exchange,status,"application/json; charset=utf-8",Json.write(value));
    }
    private static void respondText(HttpExchange exchange,int status,String contentType,String value) throws java.io.IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",contentType);
        exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(1); workers.shutdown(); }
}
