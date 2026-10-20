package com.zoutrankil.market.cn.source;

import com.zoutrankil.data.client.TushareClient;
import com.zoutrankil.data.client.dto.TusharePage;
import com.zoutrankil.data.client.dto.TushareRequest;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.service.PageExecutor;
import com.zoutrankil.data.service.TushareCursorAdapter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** 保留名称变更的年度游标、2010 年锚点和原取消协议，不让通用客户端猜业务范围。 */
public final class NameChangeCursorAdapter implements TushareCursorAdapter {
    @Override
    public boolean supports(PageContract contract) {
        return contract.endpoint().equals("namechange")
                && contract.paging() == PageContract.Paging.CURSOR;
    }

    @Override
    public PageExecutor.Fetcher fetcher(
            TushareClient client, PageContract contract, BooleanSupplier cancelled) {
        return params -> fetch(client, contract, params, cancelled);
    }

    private PageExecutor.Page fetch(
            TushareClient client,
            PageContract contract,
            Map<String, Object> params,
            BooleanSupplier cancelled)
            throws Exception {
        String tsCode = (String) params.get("ts_code");
        int year = 2010, offset = 0;
        Object token = params.get("cursor");
        if (token instanceof String value) {
            String[] pieces = value.split(":", -1);
            if (pieces.length != 2) throw new IllegalArgumentException("Invalid namechange cursor");
            year = Integer.parseInt(pieces[0]);
            offset = Integer.parseInt(pieces[1]);
        }
        int endYear =
                LocalDate.parse((String) params.get("end_date"), DateTimeFormatter.BASIC_ISO_DATE)
                        .getYear();
        if (year > endYear) return new PageExecutor.Page(List.of(), null, true, null);
        {
            if (cancelled.getAsBoolean())
                throw new CancellationException("Source request cancelled");
            var query = new LinkedHashMap<String, Object>();
            query.put("start_date", String.format(Locale.ROOT, "%04d0101", year));
            query.put(
                    "end_date",
                    year == endYear
                            ? params.get("end_date")
                            : String.format(Locale.ROOT, "%04d1231", year));
            if (tsCode != null) query.put("ts_code", tsCode);
            query.put("limit", contract.pageSize());
            query.put("offset", offset);
            var future =
                    client.requestAsync(
                            new TushareRequest(
                                    contract.endpoint(),
                                    query,
                                    contract.fields(),
                                    contract.pageSize()));
            TusharePage fetched;
            try {
                while (true) {
                    if (cancelled.getAsBoolean())
                        throw new CancellationException("Source request cancelled");
                    try {
                        fetched = future.get(100, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException waiting) {
                    }
                }
            } finally {
                if (!future.isDone()) future.cancel(true);
            }
            int count = fetched.rows().size();
            if (count >= contract.pageSize())
                return new PageExecutor.Page(
                        fetched.rows(), year + ":" + Math.addExact(offset, count), false, null);
            if (year == endYear) return new PageExecutor.Page(fetched.rows(), null, true, null);
            return new PageExecutor.Page(fetched.rows(), (year + 1) + ":0", false, null);
        }
    }
}
