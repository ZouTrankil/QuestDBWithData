package com.zoutrankil.data.l2live.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;

/** One bounded daily session, admitted by the platform's authoritative exchange calendar. */
record L2TradingSession(LocalDate date, Instant start, Instant stop, boolean open) {
    boolean expired(Clock clock) { return !clock.instant().isBefore(stop); }
    boolean waiting(Clock clock) { return clock.instant().isBefore(start); }

    static L2TradingSession resolve(L2LiveConfiguration config, Clock clock) throws Exception {
        var date = LocalDate.now(clock.withZone(config.sessionZone));
        var start = date.atTime(config.sessionStart).atZone(config.sessionZone).toInstant();
        var stop = date.atTime(config.sessionStop).atZone(config.sessionZone).toInstant();
        // Calendar dates are UTC date markers, independently of the wall-clock session zone.
        String sql = "SELECT is_open FROM exchange_calendar WHERE exchange='" + config.sessionExchange
                + "' AND cal_date>='" + date + "T00:00:00.000000Z' AND cal_date<'"
                + date.plusDays(1) + "T00:00:00.000000Z' LIMIT 2";
        var uri = URI.create(config.questDb.replaceAll("/+$", "") + "/exec?query="
                + URLEncoder.encode(sql, StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(uri).timeout(config.visibilityTimeout).GET();
        if (!config.questDbAuthorization.isBlank()) request.header("Authorization", config.questDbAuthorization);
        try (var client = HttpClient.newBuilder().connectTimeout(config.visibilityTimeout).build()) {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (var body = response.body()) { bytes = body.readNBytes(65_537); }
            if (response.statusCode() != 200 || bytes.length > 65_536)
                throw new IllegalStateException("TRADING_CALENDAR_UNAVAILABLE");
            var json = new ObjectMapper().readTree(bytes);
            var columns = json.path("columns");
            var rows = json.path("dataset");
            if (!columns.isArray() || columns.size() != 1 || !columns.get(0).path("name").asText().equals("is_open")
                    || !rows.isArray() || rows.size() != 1 || !rows.get(0).isArray() || rows.get(0).size() != 1
                    || !rows.get(0).get(0).isIntegralNumber() || !rows.get(0).get(0).canConvertToInt())
                throw new IllegalStateException("TRADING_CALENDAR_MISSING_OR_AMBIGUOUS");
            int flag = rows.get(0).get(0).intValue();
            if (flag != 0 && flag != 1) throw new IllegalStateException("TRADING_CALENDAR_INVALID_FLAG");
            return new L2TradingSession(date, start, stop, flag == 1);
        }
    }
}
