package com.zoutrankil.batch.l2;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class L2BatchState {
    private L2BatchState() {
    }

    record Job(String symbol, List<Path> sources) {
    }

    record SourceFingerprint(String sha256, Map<String, Object> files) {
    }

    record Outcome(String symbol, String status, boolean resumed, List<String> sourceDirectories,
        String inputFingerprint, Map<String, Object> inputFiles, String resultSha256,
        Map<String, Long> rows, double elapsedSeconds, String error) {
        static Outcome failure(String symbol, List<String> sources, Exception failure) {
            return new Outcome(symbol, "FAILED", false, sources, null, Map.of(), null, Map.of(), 0,
                failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        Outcome withElapsed(double seconds) {
            return new Outcome(symbol, status, resumed, sourceDirectories, inputFingerprint, inputFiles, resultSha256, rows, seconds, error);
        }
        Map<String, Object> record() {
            var map = new LinkedHashMap<String, Object>();
            map.put("symbol", symbol);
            map.put("status", status);
            map.put("resumed", resumed);
            map.put("sourceDirectories", sourceDirectories);
            map.put("inputFingerprint", inputFingerprint);
            map.put("inputFiles", inputFiles);
            map.put("resultSha256", resultSha256);
            map.put("rows", rows);
            map.put("elapsedSeconds", elapsedSeconds);
            map.put("error", error);
            return map;
        }
    }
}
