package com.zoutrankil.batch;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChinabondYieldSourceTest {
    private static String fixture(String date) {
        var html=new StringBuilder("<html><table><tr><td>查询</td></tr></table><table><tr>");
        for(String header:List.of("曲线名称","日期","3月","6月","1年","3年","5年","7年","10年","30年")) html.append("<th>").append(header).append("</th>");
        html.append("</tr>");
        for(String name:List.of("中债国债收益率曲线","中债中短期票据收益率曲线(AAA)","中债商业银行普通债收益率曲线(AAA)"))
            html.append("<tr><td>").append(name).append("</td><td>").append(date).append("</td><td>1.1</td><td>1.2</td><td>1.3</td><td>1.4</td><td>1.5</td><td>1.6</td><td>1.7</td><td>-</td></tr>");
        return html.append("</table></html>").toString();
    }
    @Test void parsesOnlyTheDeclaredDataTableAndPreservesMissingTenorAsNull() throws Exception {
        var rows=ChinabondYieldSource.parse(fixture("2026-09-28"),LocalDate.of(2026,9,28));
        assertEquals(3,rows.size());assertEquals("中债国债收益率曲线",rows.getFirst().get("raw_curve_name").asText());
        assertNull(rows.getFirst().get("raw_30y").textValue());
        assertTrue(ChinabondYieldSource.parse(fixture("2026-09-28"),LocalDate.of(2026,9,29)).isEmpty());
        assertThrows(IllegalArgumentException.class,()->ChinabondYieldSource.parse("<html>not a table</html>",LocalDate.of(2026,9,28)));
    }
}
