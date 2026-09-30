package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.repository.ExchangeCalendarWritePort;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.domain.SyncJobDefinition.*;

/** Single calendar owner adapter; request bounds are frozen before source or write access. */
public final class ExchangeCalendarSyncAdapter implements SyncJobRunner.Adapter<ExchangeCalendar,ExchangeCalendar.Key> {
    private final ExchangeCalendarSource source;
    private final ExchangeCalendarWritePort port;
    private final Path evidence;
    public ExchangeCalendarSyncAdapter(ExchangeCalendarSource source,ExchangeCalendarWritePort port,Path evidence) {
        this.source=Objects.requireNonNull(source);this.port=Objects.requireNonNull(port);this.evidence=evidence;
    }
    public static SyncJobDefinition definition(boolean enabled) {
        return new SyncJobDefinition("data.exchange_calendar",1,"exchange_calendar",1,"exchange_calendar_owner",
                Set.of(Mode.INCREMENTAL,Mode.BACKFILL,Mode.RECONCILE),Mode.INCREMENTAL,
                Map.of("exchanges",new Parameter(ParameterType.STRING_LIST,true,4,2,Set.of("SSE","SZSE"))),
                "tushare.shared","exchange_calendar.year","questdb.full_key_values",
                new RetryPolicy(3,Duration.ofSeconds(1),Duration.ofMinutes(2)),Duration.ofMinutes(20),
                new Budget(3660,24,24,7320,1024*1024),366,List.of(),Frequency.DAILY,
                ZoneId.of("Asia/Shanghai"),enabled,true);
    }
    @SuppressWarnings("unchecked")
    private static List<String> exchanges(FrozenRequest request) {
        Object value=request.parameters().get("exchanges");
        if(!(value instanceof List<?> rows) || rows.stream().anyMatch(x->!(x instanceof String)))
            throw new IllegalArgumentException("Typed exchanges required");
        return (List<String>) rows;
    }
    private static List<ExchangeCalendarSlices.Slice> slices(FrozenRequest request) {
        var d=request.definition();
        if(!d.jobId().equals("data.exchange_calendar") || d.version()!=1 || !d.datasetId().equals("exchange_calendar")
                || d.datasetVersion()!=1 || !Set.of(Mode.INCREMENTAL,Mode.BACKFILL,Mode.RECONCILE).contains(request.mode()))
            throw new IllegalArgumentException("Calendar job definition/version/mode mismatch");
        var slices=ExchangeCalendarSlices.bounded(exchanges(request),request.from(),request.to());
        int rows=slices.stream().mapToInt(ExchangeCalendarSlices.Slice::expectedDays).sum();
        if(slices.size()>d.budget().maxPages() || slices.size()>d.budget().maxSlices() || rows>d.budget().maxRows())
            throw new IllegalArgumentException("Calendar request exceeds frozen source budget");
        return slices;
    }
    public void preflight(FrozenRequest request) { slices(request);port.preflight(); }
    public SyncJobRunner.SourceCompletion fetch(FrozenRequest request,SyncJobRunner.PageConsumer<ExchangeCalendar> consumer,
                                                BooleanSupplier cancelled) throws Exception {
        int pages=0,rows=0;var files=new ArrayList<String>();
        for(var slice:slices(request)) {
            if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Calendar sync cancelled");
            var page=source.fetch(slice,cancelled);
            consumer.accept(page);pages++;rows+=page.rows().size();files.add(page.responseEvidence());
        }
        Files.createDirectories(evidence);
        Path file=evidence.resolve("complete-"+UUID.randomUUID()+".json");
        JobDefinitionJson.mapper().writeValue(file.toFile(),Map.of("complete",true,"pages",pages,"rows",rows,
                "from",request.from(),"to",request.to(),"exchanges",exchanges(request),"sourceEvidence",files));
        return new SyncJobRunner.SourceCompletion(pages,rows,true,file.toString());
    }
    public VerifiedBatchExecutor.Codec<ExchangeCalendar,ExchangeCalendar.Key> codec() { return ExchangeCalendarWritePort.CODEC; }
    public VerifiedBatchExecutor.Port<ExchangeCalendar,ExchangeCalendar.Key> port() { return port; }
}
