package com.zoutrankil.questdbwithdata.service;

import com.zoutrankil.questdbwithdata.domain.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;

/** Split potentially truncated date windows before delivering their rows. One page in memory. */
public final class AdaptiveSliceExecutor {
    public record Completed(int sourceRequests, int completedWindows, int deliveredRows) {}
    public Completed execute(PageContract contract, SyncSlice initial, Map<String, Object> baseParams,
                             String startParameter, String endParameter, int minimumDays, int maxRequests,
                             int maxRows, PageExecutor.Fetcher fetcher,
                             Function<SyncSlice, PageExecutor.Validator> validatorFactory,
                             SliceConsumer consumer, BooleanSupplier cancelled) throws Exception {
        if (contract.paging() != PageContract.Paging.NONE || initial.periodEnd() != null) {
            throw new IllegalArgumentException("Adaptive splitting requires an unpaged date-window source");
        }
        if (startParameter == null || endParameter == null || startParameter.equals(endParameter)
                || !contract.allowedParameters().containsAll(List.of(startParameter, endParameter))
                || baseParams.containsKey(startParameter) || baseParams.containsKey(endParameter)) {
            throw new IllegalArgumentException("Distinct supported date parameters required; executor owns window bounds");
        }
        if (minimumDays < 1 || maxRequests < 1 || maxRequests > 10000 || maxRows < 1 || maxRows > 1000000) {
            throw new IllegalArgumentException("Finite adaptive execution budget required");
        }
        var pending = new ArrayDeque<SyncSlice>();
        pending.push(initial);
        int[] calls = {0}, rows = {0};
        int completed = 0;
        var executor = new PageExecutor();
        while (!pending.isEmpty()) {
            if (cancelled.getAsBoolean()) throw new PageExecutor.Incomplete("Adaptive run cancelled", completed, rows[0]);
            var slice = pending.pop();
            var params = new LinkedHashMap<>(baseParams);
            params.put(startParameter, slice.start().format(DateTimeFormatter.BASIC_ISO_DATE));
            params.put(endParameter, slice.end().format(DateTimeFormatter.BASIC_ISO_DATE));
            try {
                executor.execute(contract, params, request -> {
                    if (++calls[0] > maxRequests) throw new IllegalStateException("Adaptive request budget exhausted");
                    return fetcher.fetch(request);
                }, (page, receipt) -> {
                    if ((long) rows[0] + page.rows().size() > maxRows) throw new IllegalStateException("Adaptive row budget exhausted");
                    consumer.accept(slice, page, receipt);
                    rows[0] += page.rows().size();
                }, validatorFactory.apply(slice), cancelled);
                completed++;
            } catch (PageExecutor.Truncated cap) {
                if (slice.days() < 2L * minimumDays) {
                    throw new PageExecutor.Incomplete("Minimum date window still reaches source cap", completed, rows[0]);
                }
                var middle = slice.start().plusDays(slice.days() / 2 - 1);
                pending.push(new SyncSlice(middle.plusDays(1), slice.end(), slice.code(), slice.category(), null));
                pending.push(new SyncSlice(slice.start(), middle, slice.code(), slice.category(), null));
            }
        }
        return new Completed(calls[0], completed, rows[0]);
    }
    @FunctionalInterface public interface SliceConsumer {
        void accept(SyncSlice slice, PageExecutor.Page page, PageExecutor.Receipt receipt) throws Exception;
    }
}
