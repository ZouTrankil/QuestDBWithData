package com.zoutrankil.batch.l2;

import com.zoutrankil.batch.l2.L2BatchState.Job;
import com.zoutrankil.batch.l2.L2BatchState.SourceFingerprint;
import com.zoutrankil.batch.l2.archive.DfcfCsvParser;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

interface L2DailyFeatureSource {
    // 来源语义变化时升级版本，避免复用旧结果。
    default String parserVersion() {
        return L2DailyFeaturePipeline.PARSER_VERSION;
    }

    List<Job> discover(Path source, List<String> ignored) throws IOException;

    SourceFingerprint fingerprint(Path source, long maxBytes) throws IOException;

    DfcfCsvParser.ProductionDay parse(Path source, String symbol, LocalDate date, long maxBytes)
            throws IOException;
}
