package com.zoutrankil.data.derived.application;

import com.zoutrankil.data.service.VerifiedBatchExecutor;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.derived.port.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.zoutrankil.data.derived.port.EtfMarketOverviewPublicationCodec.*;
import static com.zoutrankil.data.derived.domain.EtfMarketOverviewPublicationValues.sameCache;

/** One run's delegated publication state; no independent sender or receipt writer. */
public final class DefaultEtfMarketOverviewPublicationSession implements EtfMarketOverviewPublicationSession {
    private final EtfMarketOverviewSource source;
    private final EtfMarketOverviewPublisher publisher;
    private final EtfMarketOverviewPublicationTarget target;
    private EtfMarketOverviewCachePublicationEnvelope bound;
    private BooleanSupplier cancelled=()->false;
    private VerifiedBatchExecutor.Submission submission;
    private boolean unresolved,knownStopped,attempted;
    private Path reconciliationLedger;
    private String reconciliationRun;
    private final Set<String> attemptedSlices=new HashSet<>();
    public DefaultEtfMarketOverviewPublicationSession(EtfMarketOverviewSource source,
            EtfMarketOverviewPublisher publisher, EtfMarketOverviewPublicationTarget target,
            EtfMarketOverviewCachePublicationEnvelope envelope) {
        this.source=Objects.requireNonNull(source);this.publisher=Objects.requireNonNull(publisher);
        this.target=Objects.requireNonNull(target);bind(envelope);
    }
    /** Pure binding: the shared runner must first create durable run/attempt authority. */
    public void bind(EtfMarketOverviewCachePublicationEnvelope envelope){bound=Objects.requireNonNull(envelope);submission=null;knownStopped=false;attempted=false;}
    public void cancellationProbe(BooleanSupplier probe){cancelled=Objects.requireNonNull(probe);}
    public Duration visibilityTimeout(){return Duration.ofSeconds(30);}
    public boolean unresolved(){return unresolved;}
    public void reconciliationContext(Path ledgerPath,String runId){reconciliationLedger=Objects.requireNonNull(ledgerPath).toAbsolutePath().normalize();reconciliationRun=Objects.requireNonNull(runId);if(runId.isBlank())throw new IllegalArgumentException("Run identity required");}
    public EtfMarketOverviewCachePublicationEnvelope preview(LocalDate date)throws Exception{return source.preview(date);}
    public EtfMarketOverviewCachePublicationEnvelope requireExactEnvelope(EtfMarketOverviewDailyCache row)throws Exception{
        Objects.requireNonNull(row);var expected=source.preview(row.tradeDate());
        if(!expected.knownSourceDate()||expected.cache()==null||!sameCache(expected.cache(),row))throw new IllegalArgumentException("Prepared caller differs from the current original-owner full five-field preview");return expected;
    }
    @Override public void preflight()throws Exception{target.preflight(bound);}
    @Override public void submissionRecorded(VerifiedBatchExecutor.Submission value)throws Exception{
        if(attempted||unresolved)throw new IllegalStateException("Uncertain original publication cannot be submitted again");
        publisher.validateSubmission(bound,value);submission=value;
    }
    @Override public void send(List<EtfMarketOverviewCachePublicationEnvelope> rows)throws Exception{
        if(rows==null||rows.size()!=1||!bound.knownSourceDate()||!CODEC.equivalent(bound,rows.getFirst())
                ||!bound.sourceFingerprint().equals(rows.getFirst().sourceFingerprint())||submission==null||attempted||unresolved)
            throw new IllegalStateException("One exact known-day publication and its durable submission are required");
        String identity=submission.ledgerPath()+":"+submission.sliceId();if(!attemptedSlices.add(identity))throw new IllegalStateException("Publication slice already attempted; reconcile without resend");
        if(cancelled.getAsBoolean())throw new IllegalStateException("Cancelled before owner invocation");
        preflight();attempted=true;unresolved=true;knownStopped=false;
        // From this boundary onward any exception is unknown. No automatic resend or implicit recovery.
        var result=publisher.publish(bound,submission,cancelled);
        if(result==null||!result.processStopped()||!"VERIFIED_READTHROUGH".equals(result.status()))throw new IOException("Original owner publication lacks actual termination proof");
        knownStopped=true;unresolved=false;
    }
    @Override public List<EtfMarketOverviewCachePublicationEnvelope> readback(List<EtfMarketOverviewDailyCacheKey> keys)throws Exception{
        if(keys==null||keys.size()!=1||!keys.getFirst().equals(bound.key())||!bound.knownSourceDate())throw new IllegalArgumentException("Exact single complete publication key required");
        var observed=target.readback(bound,bound.key());
        List<EtfMarketOverviewDailyCache> cache=observed.cache();List<MarketBarometerCacheCoverage> receipts=observed.receipts();
        if(cache.size()>1||receipts.size()>1)throw new IllegalStateException("Duplicate complete owner publication key");
        if(receipts.isEmpty())return List.of();
        var actualCache=cache.isEmpty()?null:cache.getFirst();var actualReceipt=receipts.getFirst();
        if(!bound.receipt().equals(actualReceipt)||!sameCache(bound.cache(),actualCache))return List.of();
        return List.of(bound.withActual(actualCache,actualReceipt));
    }
    @Override public boolean walSettled()throws Exception{return target.walSettled(bound);}
    @Override public boolean uncertainSenderStopped()throws Exception{
        // An unknown attempt stays IN_DOUBT until an explicit run-scoped investigation.
        if(reconciliationLedger!=null)return publisher.writerStopped(reconciliationLedger,reconciliationRun);
        return knownStopped&&!unresolved;
    }
}
