package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.questdbwithdata.client.TushareClient;
import com.zoutrankil.questdbwithdata.client.dto.*;
import com.zoutrankil.questdbwithdata.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TushareNamechangePagingTest {
    @Test void emptyAnnualWindowsArePageReceiptsAndOnlyLastYearTerminates() throws Exception {
        var client=mock(TushareClient.class);var requests=new ArrayList<TushareRequest>();
        when(client.requestAsync(any())).thenAnswer(invocation -> {
            TushareRequest request=invocation.getArgument(0);requests.add(request);
            String start=(String)request.params().get("start_date");
            List<Map<String,JsonNode>> rows=start.equals("20260101")?List.of(Map.of(
                    "ts_code",Json.MAPPER.valueToTree("000001.SZ"),"name",Json.MAPPER.valueToTree("ST A"),
                    "start_date",Json.MAPPER.valueToTree("20260101"),"end_date",Json.MAPPER.nullNode(),
                    "ann_date",Json.MAPPER.valueToTree("20260101"),"change_reason",Json.MAPPER.valueToTree("ST"))):List.of();
            return CompletableFuture.completedFuture(new TusharePage(request.fields(),rows));
        });
        var contract=SourceContract.load("stk_st_daily");var executor=new PageExecutor();int[] seen={0};
        var completed=executor.execute(contract.pageContract(),Map.of("start_date","20100101","end_date","20260928","ts_code","000001.SZ"),
                new TusharePageService(client).fetcher(contract.providerContract(),()->false),
                (page,receipt)->seen[0]+=page.rows().size(),row->{},()->false);
        assertEquals(17,completed.pages());assertEquals(1,completed.rows());assertEquals(1,seen[0]);assertEquals(17,requests.size());
        assertEquals("20100101",requests.getFirst().params().get("start_date"));
        assertEquals("20260928",requests.getLast().params().get("end_date"));
        assertEquals("20260101",requests.get(16).params().get("start_date"));
    }
}
