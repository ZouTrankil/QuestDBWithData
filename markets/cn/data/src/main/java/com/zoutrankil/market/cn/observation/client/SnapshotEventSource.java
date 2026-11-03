package com.zoutrankil.market.cn.observation.client;

import com.zoutrankil.data.domain.DatasetDefinition.StorageType;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.PageContract;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.PageExecutor.Page;
import com.zoutrankil.data.service.TusharePageService;
import com.zoutrankil.market.cn.observation.mapper.SnapshotEventDefinition;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/** 按完整公告日／交易日抓取原文，保留重复行；达到来源容量时拒绝签完整。 */
public final class SnapshotEventSource {
    public record Frozen(
            String dataset,
            LocalDate date,
            List<Map<String, Object>> rows,
            String rawPath,
            String rawSha256) {}

    private final TusharePageService pages;

    public SnapshotEventSource(TusharePageService pages) {
        this.pages = pages;
    }

    public Frozen collect(String dataset, LocalDate date, Path folder, BooleanSupplier cancelled)
            throws Exception {
        var definition = SnapshotEventDefinition.definition(dataset);
        var fields =
                definition.columns().stream()
                        .map(c -> c.sourceName())
                        .filter(n -> !n.startsWith("derived:"))
                        .toList();
        int limit = SnapshotEventDefinition.sourceLimit(dataset);
        var contract =
                new PageContract(
                        dataset,
                        fields,
                        List.of("ts_code", SnapshotEventDefinition.dateField(dataset)),
                        Set.of(SnapshotEventDefinition.dateField(dataset)),
                        PageContract.Paging.NONE,
                        PageContract.Completion.SHORT_PAGE,
                        null,
                        null,
                        limit,
                        limit,
                        1,
                        limit,
                        "完整单日多重记录；容量满额拒绝完整，不假设 offset 分页");
        var params =
                Map.<String, Object>of(
                        SnapshotEventDefinition.dateField(dataset),
                        date.format(DateTimeFormatter.BASIC_ISO_DATE));
        var page = pages.fetcher(contract, cancelled).fetch(params);
        Path raw = folder.resolve("source-raw.json");
        byte[] bytes =
                JobDefinitionJson.mapper()
                        .writeValueAsBytes(
                                Map.of(
                                        "dataset",
                                        dataset,
                                        "date",
                                        date,
                                        "parameters",
                                        params,
                                        "page",
                                        page));
        if (bytes.length > 16 * 1024 * 1024) throw new IllegalStateException("来源原文超出16MiB预算");
        FileEvidenceStore.writeNewDurable(raw, bytes);
        return replay(
                new Frozen(
                        dataset,
                        date,
                        List.of(),
                        raw.toAbsolutePath().toString(),
                        FileEvidenceStore.sha256(bytes)));
    }

    /** 恢复只投影原响应，重新校验范围及容量，不调用供应商重抓。 */
    public static Frozen replay(Frozen frozen) throws Exception {
        Path raw = Path.of(frozen.rawPath());
        byte[] bytes =
                FileEvidenceStore.readBounded(
                        raw, 16 * 1024 * 1024, () -> new IllegalStateException("来源原文超出预算"));
        if (!FileEvidenceStore.sha256(bytes).equals(frozen.rawSha256()))
            throw new IllegalStateException("原来源摘要不符");
        var json = JobDefinitionJson.mapper();
        var root = json.readTree(bytes);
        String dataset = frozen.dataset();
        LocalDate date = frozen.date();
        if (!dataset.equals(root.required("dataset").asText())
                || !date.toString().equals(root.required("date").asText())
                || !root.required("parameters")
                        .equals(
                                json.valueToTree(
                                        Map.of(
                                                SnapshotEventDefinition.dateField(dataset),
                                                date.format(DateTimeFormatter.BASIC_ISO_DATE)))))
            throw new IllegalArgumentException("原来源请求范围不符");
        var page = json.treeToValue(root.required("page"), Page.class);
        if (page.rows().size() >= SnapshotEventDefinition.sourceLimit(dataset)
                || page.nextCursor() != null)
            throw new IllegalStateException("来源原文未证明单日完整，保留原文且不建立 stage");
        var definition = SnapshotEventDefinition.definition(dataset);
        var rows = new ArrayList<Map<String, Object>>();
        for (var source : page.rows()) {
            var row = new TreeMap<String, Object>();
            for (var column : definition.columns()) {
                String name = column.sourceName();
                if (name.startsWith("derived:")) continue;
                var n = source.get(name);
                if (n == null) throw new IllegalArgumentException("来源缺列：" + name);
                Object value;
                if (n.isNull()) value = null;
                else if (name.equals(SnapshotEventDefinition.dateField(dataset))) {
                    if (!n.isTextual()
                            || !n.asText().equals(date.format(DateTimeFormatter.BASIC_ISO_DATE)))
                        throw new IllegalArgumentException("来源业务日越出冻结范围");
                    value = date.atStartOfDay(ZoneOffset.UTC).toInstant().toString();
                } else if (column.storageType() == StorageType.DOUBLE) {
                    if (!n.isNumber() || !Double.isFinite(n.doubleValue()))
                        throw new IllegalArgumentException("来源数值无效：" + name);
                    value = n.doubleValue();
                } else {
                    if (!n.isTextual()) throw new IllegalArgumentException("来源文本类型无效：" + name);
                    value = n.textValue();
                    if (Set.of("end_date", "exp_date").contains(name) && !n.textValue().isBlank())
                        LocalDate.parse(n.textValue(), DateTimeFormatter.BASIC_ISO_DATE);
                }
                row.put(column.storageName(), value);
            }
            if (!(row.get("ts_code") instanceof String code)
                    || !code.matches("[0-9]{6}\\.(SH|SZ|BJ)"))
                throw new IllegalArgumentException("来源证券代码无效");
            SnapshotEventDefinition.date(dataset, row);
            rows.add(row);
        }
        return new Frozen(
                dataset,
                date,
                SnapshotEventDefinition.assignOrdinals(dataset, rows),
                raw.toAbsolutePath().toString(),
                FileEvidenceStore.sha256(bytes));
    }
}
