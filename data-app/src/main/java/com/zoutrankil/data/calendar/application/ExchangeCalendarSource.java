package com.zoutrankil.data.calendar.application;

import com.zoutrankil.data.service.*;

import com.zoutrankil.data.repository.FileEvidenceStore;
import com.fasterxml.jackson.databind.*;
import com.zoutrankil.data.client.dto.TushareTradeCalendarDto;
import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.calendar.mapper.ExchangeCalendarMapper;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded trade_cal request using the common cancellable HTTP/retry/rate budget path. */
public final class ExchangeCalendarSource {
    private final TusharePageService pages;
    private final Path evidence;
    private final ExchangeCalendarMapper mapper = new ExchangeCalendarMapper();
    public ExchangeCalendarSource(TusharePageService pages,Path evidence) {
        this.pages=Objects.requireNonNull(pages);this.evidence=evidence.toAbsolutePath().normalize();
    }
    public SyncJobRunner.Page<ExchangeCalendar> fetch(ExchangeCalendarSlices.Slice slice,BooleanSupplier cancelled)
            throws Exception {
        var fields=List.of("exchange","cal_date","is_open","pretrade_date");
        var contract=new PageContract("trade_cal",fields,List.of("exchange","cal_date"),
                Set.of("exchange","start_date","end_date"),PageContract.Paging.NONE,
                PageContract.Completion.SHORT_PAGE,null,null,367,367,1,367,
                "One exchange and at most one calendar year; exact day coverage checked independently; no offset");
        Map<String,Object> parameters=Map.of("exchange",slice.exchange(),
                "start_date",slice.from().format(DateTimeFormatter.BASIC_ISO_DATE),
                "end_date",slice.to().format(DateTimeFormatter.BASIC_ISO_DATE));
        var result=new ArrayList<SyncJobRunner.Page<ExchangeCalendar>>();
        var json=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        var completion=pages.execute(contract,parameters,(page,receipt)-> {
            byte[] body=json.writeValueAsBytes(Map.of("endpoint","trade_cal","parameters",parameters,"rows",page.rows()));
            Files.createDirectories(evidence);
            Path file=evidence.resolve("source-"+UUID.randomUUID()+".json");
            FileEvidenceStore.writeNew(file,body);
            var typed=new ArrayList<ExchangeCalendar>();
            for (var row:page.rows()) {
                JsonNode flag=row.get("is_open");
                if (flag==null || !flag.isIntegralNumber() || !flag.canConvertToInt())
                    throw new IllegalArgumentException("Exact integer calendar flag required");
                typed.add(mapper.fromSource(new TushareTradeCalendarDto(text(row,"exchange"),text(row,"cal_date"),
                        flag.intValue(),text(row,"pretrade_date"))));
            }
            var complete=ExchangeCalendarSlices.complete(slice,typed);
            result.add(new SyncJobRunner.Page<>(complete,FileEvidenceStore.sha256(body),file.toString(),null));
        },row->{
            if (!slice.exchange().equals(text(row,"exchange"))) throw new IllegalArgumentException("Unexpected exchange in source");
        },cancelled);
        if (result.size()!=1 || completion.rows()!=slice.expectedDays())
            throw new IllegalStateException("Calendar source did not prove complete daily coverage; no write permitted");
        return result.getFirst();
    }
    private static String text(Map<String,JsonNode> row,String name) {
        var node=row.get(name);
        if (node==null || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException("Text calendar field required: "+name);
        return node.textValue();
    }
}
