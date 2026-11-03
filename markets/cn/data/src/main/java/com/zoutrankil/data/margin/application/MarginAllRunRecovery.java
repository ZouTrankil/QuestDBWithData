package com.zoutrankil.data.margin.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.SyncJobDefinition;
import com.zoutrankil.data.domain.SyncRequestIdentity;
import com.zoutrankil.data.domain.SyncRunState;
import com.zoutrankil.data.margin.port.MarginAllTables;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.repository.SyncRunLedger;
import com.zoutrankil.data.service.DatasetIntervalLock;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 显式认证原发布的来源和账本；不抓来源、不发送数据、不代替物理发布恢复。 */
public final class MarginAllRunRecovery {
    private MarginAllRunRecovery() {}

    private record Slice(SyncRunLedger.Entry entry, JsonNode fetched, int rows) {}

    public static Map<String, Object> reconcile(
            MarginAllTables tables,
            Path ledgerPath,
            String table,
            String runId,
            boolean writerStopped)
            throws Exception {
        if (!writerStopped) throw new IllegalArgumentException("需要原writer实际停止的明确证据");
        ledgerPath = ledgerPath.toAbsolutePath().normalize();
        var ledger = SyncRunLedger.openExisting(ledgerPath);
        var run = ledger.getRun(runId);
        var request = request(run);
        if (!run.targetId().equals(tables.logicalTargetId(table))
                || "margin_all".equals(table) != (run.jobVersion() == 2))
            throw new IllegalStateException("原运行与逻辑目标或版本不符");
        var publication = new MarginAllPublication(tables, ledgerPath);
        try (var operation = publication.beginOperation(runId)) {
            var verified = publication.verifiedPublication(operation, runId);
            if (!table.equals(verified.entry().intent().target()))
                throw new IllegalStateException("发布逻辑目标不属于原运行");
            var originalRoot = ledgerPath.getParent().resolve("sync-evidence").resolve(runId);
            if (Files.isSymbolicLink(originalRoot)
                    || !Files.isDirectory(originalRoot, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("原运行证据目录无效");
            var root = originalRoot.toRealPath();
            var scope = JobDefinitionJson.mapper().readTree(verified.entry().intent().scope());
            var receipt = owned(root, scope.path("stageReceipt").asText());
            byte[] bytes =
                    FileEvidenceStore.readBounded(
                            receipt,
                            4 * 1024 * 1024,
                            () -> new IllegalStateException("stage凭证超预算"));
            if (!FileEvidenceStore.sha256(bytes)
                    .equals(scope.path("stageReceiptFingerprint").asText()))
                throw new IllegalStateException("stage凭证摘要变化");
            var stage = JobDefinitionJson.mapper().readTree(bytes);
            var refs = stage.path("sourceReceipts");
            var slices = validateSlices(ledger, run, request, root, refs);
            int rows = slices.stream().mapToInt(Slice::rows).sum();
            if (rows != stage.path("sourceRows").asInt(-1))
                throw new IllegalStateException("来源总数与原发布凭证不符");
            var children = ledger.entries(runId, null, 1000);
            var attempt =
                    children.stream()
                            .filter(e -> e.kind() == SyncRunLedger.Kind.ATTEMPT)
                            .findFirst()
                            .orElseThrow();
            var end = rows == 0 ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED;
            var locks = new DatasetIntervalLock(ledgerPath);
            var interval =
                    new DatasetIntervalLock.Scope("margin_all", request.from(), request.to());
            var lease = locks.findOwned(runId, interval);
            var intent = root.resolve("ledger-finalization-intent.json");
            boolean alreadyComplete =
                    ledger.get(runId).state() == end
                            && ledger.get(attempt.id()).state() == end
                            && slices.stream()
                                    .allMatch(
                                            s ->
                                                    s.entry().state()
                                                            == (s.rows() == 0
                                                                    ? SyncRunState.VERIFIED_EMPTY
                                                                    : SyncRunState.VERIFIED));
            if (lease == null && (!alreadyComplete || !Files.isRegularFile(intent)))
                throw new IllegalStateException("缺少原运行保留租约或已验证的收尾意图");
            String leaseId =
                    lease == null
                            ? JobDefinitionJson.mapper()
                                    .readTree(
                                            FileEvidenceStore.readBounded(
                                                    intent,
                                                    1024 * 1024,
                                                    () -> new IllegalStateException("收尾意图超预算")))
                                    .path("leaseId")
                                    .asText()
                            : lease.id();
            if (leaseId.isBlank()) throw new IllegalStateException("原租约身份缺失");
            var proof = new LinkedHashMap<String, Object>();
            proof.put("schema", "cn.margin-all.ledger-finalization.v1");
            proof.put("runId", runId);
            proof.put(
                    "requestFingerprint", SyncRequestIdentity.fingerprint(request, run.targetId()));
            proof.put("publicationId", verified.entry().intent().id());
            proof.put("targetId", run.targetId());
            proof.put("leaseId", leaseId);
            proof.put("writerStopped", true);
            proof.put("sourceRows", rows);
            proof.put("sourceReceipts", refs);
            proof.put("stageReceipt", receipt.toString());
            proof.put("stageReceiptSha256", FileEvidenceStore.sha256(bytes));
            proof.put("targetFingerprint", verified.target().fingerprint());
            proof.put("backupFingerprint", verified.backup().fingerprint());
            // 所有原件和完整回读先通过，耐久登记后才能改子账本；崩溃后沿同一意图继续。
            writeOrMatch(intent, proof);
            String fingerprint =
                    FileEvidenceStore.sha256(
                            JobDefinitionJson.canonicalMapper().writeValueAsBytes(refs));
            for (var slice : slices)
                if (slice.entry().state() == SyncRunState.FETCHED) {
                    var current = ledger.get(slice.entry().id());
                    ledger.transition(
                            current.id(),
                            current.revision(),
                            SyncRunState.VERIFIED_EMPTY,
                            evidence(
                                    0,
                                    slice.fetched().path("sourceFingerprint").asText(),
                                    intent,
                                    verified.entry().intent().id()));
                }
            String completion = evidence(rows, fingerprint, intent, verified.entry().intent().id());
            for (var entry : List.of(attempt, ledger.get(runId))) {
                var current = ledger.get(entry.id());
                if (current.state() == end) continue;
                if (current.state() == SyncRunState.RUNNING
                        || current.state() == SyncRunState.ACKNOWLEDGED) {
                    ledger.transition(
                            current.id(),
                            current.revision(),
                            SyncRunState.IN_DOUBT,
                            "{\"marginAllExplicitFinalization\":true}");
                    current = ledger.get(current.id());
                }
                ledger.transition(current.id(), current.revision(), end, completion);
            }
            // 最后再次核对当前代和backup，状态已认证但核对失败时仍保留原锁。
            publication.verifiedPublication(operation, runId);
            writeOrMatch(root.resolve("ledger-finalization-verified.json"), proof);
            if (lease != null) {
                var current = locks.findOwned(runId, interval);
                if (current == null || !current.id().equals(leaseId))
                    throw new IllegalStateException("原租约归属改变");
                if (!current.inDoubt()) {
                    locks.retainInDoubt(current);
                    current = locks.findOwned(runId, interval);
                }
                locks.releaseAfterReconciliation(current, true, true);
            }
            return Map.of(
                    "runId",
                    runId,
                    "state",
                    end,
                    "sourceRows",
                    rows,
                    "publicationId",
                    verified.entry().intent().id(),
                    "originalLeaseReleased",
                    true,
                    "productionDataResent",
                    false,
                    "evidence",
                    root.resolve("ledger-finalization-verified.json").toString());
        }
    }

    static SyncJobDefinition.FrozenRequest request(SyncRunLedger.Run run) throws Exception {
        var json = JobDefinitionJson.mapper();
        var saved = json.readTree(run.frozenJson());
        var d = json.treeToValue(saved.path("definition"), SyncJobDefinition.class);
        if (!MarginAllSyncJobOwner.definition(run.jobVersion()).equals(d)
                || !run.jobId().equals(d.jobId())) throw new IllegalStateException("汇总原冻结定义变化");
        Map<String, Object> parameters =
                json.convertValue(
                        saved.path("parameters"),
                        new TypeReference<LinkedHashMap<String, Object>>() {});
        for (var p : d.parameters().entrySet())
            if (p.getValue().type() == SyncJobDefinition.ParameterType.DATE
                    && parameters.containsKey(p.getKey()))
                parameters.put(p.getKey(), LocalDate.parse((String) parameters.get(p.getKey())));
        var request =
                d.freeze(
                        SyncJobDefinition.Mode.valueOf(saved.path("mode").asText()),
                        parameters,
                        LocalDate.parse(saved.path("from").asText()),
                        LocalDate.parse(saved.path("to").asText()),
                        LocalDate.parse(saved.path("logicalDate").asText()));
        MarginAllSyncAdapter.validateRequest(request);
        if (!run.logicalDate().equals(request.logicalDate().toString())
                || !run.targetId().equals(parameters.get("targetId"))
                || !SyncRequestIdentity.fingerprint(request, run.targetId())
                        .equals(SyncRequestIdentity.fingerprint(run.frozenJson(), run.targetId())))
            throw new IllegalStateException("原请求身份或业务日不符");
        return request;
    }

    private static List<Slice> validateSlices(
            SyncRunLedger ledger,
            SyncRunLedger.Run run,
            SyncJobDefinition.FrozenRequest request,
            Path root,
            JsonNode refs)
            throws Exception {
        var children = ledger.entries(run.id(), null, 1000);
        if (children.size() == 1000) throw new IllegalStateException("收尾子账本超预算");
        var attempts =
                children.stream().filter(e -> e.kind() == SyncRunLedger.Kind.ATTEMPT).toList();
        var slices = children.stream().filter(e -> e.kind() == SyncRunLedger.Kind.SLICE).toList();
        if (attempts.size() != 1
                || !refs.isArray()
                || refs.size() != slices.size()
                || slices.isEmpty()
                || slices.size() > 366
                || children.size() != slices.size() + 2
                || !run.id().equals(attempts.getFirst().parentId()))
            throw new IllegalStateException("收尾要求原运行的一次尝试和全部日分片");
        var parent = ledger.get(run.id());
        if (parent.kind() != SyncRunLedger.Kind.RUN
                || !run.id().equals(parent.runId())
                || !run.id().equals(attempts.getFirst().runId()))
            throw new IllegalStateException("运行或尝试归属不符");
        for (var entry : List.of(parent, attempts.getFirst())) {
            if (!Set.of(
                            SyncRunState.RUNNING,
                            SyncRunState.ACKNOWLEDGED,
                            SyncRunState.IN_DOUBT,
                            SyncRunState.VERIFIED,
                            SyncRunState.VERIFIED_EMPTY)
                    .contains(entry.state())) throw new IllegalStateException("终态失败记录不能静默改为成功");
            latest(ledger, entry);
        }
        var byDay = new HashMap<LocalDate, JsonNode>();
        for (var ref : refs) {
            var day = LocalDate.parse(ref.path("fromInclusive").asText());
            if (!day.toString().equals(ref.path("toInclusive").asText())
                    || byDay.putIfAbsent(day, ref) != null)
                throw new IllegalStateException("原来源日期重复或非单日");
        }
        var result = new ArrayList<Slice>();
        var seen = new HashSet<LocalDate>();
        for (var slice : slices) {
            if (!run.id().equals(slice.runId())
                    || !attempts.getFirst().id().equals(slice.parentId()))
                throw new IllegalStateException("来源分片属于另一尝试");
            var events = latest(ledger, slice);
            var fetchedEvents =
                    events.stream().filter(e -> e.state() == SyncRunState.FETCHED).toList();
            if (fetchedEvents.size() != 1) throw new IllegalStateException("分片缺唯一FETCHED原件");
            var fetched =
                    JobDefinitionJson.mapper().readTree(fetchedEvents.getFirst().payloadJson());
            LocalDate day =
                    LocalDate.parse(
                            fetched.path("cursor").asText(), DateTimeFormatter.BASIC_ISO_DATE);
            var ref = byDay.get(day);
            if (ref == null || !seen.add(day)) throw new IllegalStateException("分片不属于完整原日期范围");
            var source = owned(root, fetched.path("responseEvidence").asText());
            if (!source.equals(owned(root, ref.path("responseEvidence").asText()))
                    || !fetched.path("sourceFingerprint")
                            .asText()
                            .equals(ref.path("sourceFingerprint").asText()))
                throw new IllegalStateException("FETCHED来源与发布原件不符");
            var page = MarginAllSource.reopen(source, ref.path("sourceFingerprint").asText(), day);
            int rows = page.rows().size();
            if (rows != fetched.path("returnedRows").asInt(-1)
                    || rows != ref.path("rows").asInt(-1))
                throw new IllegalStateException("分片原来源行数不符");
            if (slice.state() != (rows == 0 ? SyncRunState.VERIFIED_EMPTY : SyncRunState.VERIFIED)
                    && !(rows == 0 && slice.state() == SyncRunState.FETCHED))
                throw new IllegalStateException("存在未完整验证的非空分片，不能认证整次运行");
            if (rows == 0
                    && slice.state() == SyncRunState.FETCHED
                    && events.stream()
                            .anyMatch(
                                    e ->
                                            Set.of(
                                                            SyncRunState.SUBMITTED,
                                                            SyncRunState.ACKNOWLEDGED)
                                                    .contains(e.state())))
                throw new IllegalStateException("空来源存在异常发送记录");
            result.add(new Slice(slice, fetched, rows));
        }
        if (seen.size() != byDay.size()) throw new IllegalStateException("来源日期未完整映射到原分片");
        return List.copyOf(result);
    }

    private static List<SyncRunLedger.Event> latest(SyncRunLedger ledger, SyncRunLedger.Entry entry)
            throws Exception {
        var events = ledger.events(entry.id(), -1, 100);
        if (events.isEmpty()
                || events.size() == 100
                || events.getLast().revision() != entry.revision()
                || events.getLast().state() != entry.state())
            throw new IllegalStateException("账本状态与不可变事件历史不符");
        return events;
    }

    private static Path owned(Path root, String raw) throws Exception {
        var path = Path.of(raw).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().startsWith(root))
            throw new IllegalStateException("恢复原件越出原运行目录");
        return path.toRealPath();
    }

    private static void writeOrMatch(Path path, Map<String, Object> proof) throws Exception {
        byte[] bytes = JobDefinitionJson.canonicalMapper().writeValueAsBytes(proof);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path)
                    || !Arrays.equals(
                            bytes,
                            FileEvidenceStore.readBounded(
                                    path, 1024 * 1024, () -> new IllegalStateException("收尾原件超预算"))))
                throw new IllegalStateException("原收尾意图与本次核验不符");
        } else FileEvidenceStore.writeNewDurable(path, bytes);
    }

    private static String evidence(int rows, String fingerprint, Path proof, String publicationId)
            throws Exception {
        var verification = new LinkedHashMap<String, Object>();
        verification.put("passed", true);
        verification.put("writerStopped", true);
        verification.put("expectedRows", rows);
        verification.put("actualRows", rows);
        verification.put("matchedRows", rows);
        verification.put("mismatchedRows", 0);
        verification.put("duplicateKeys", 0);
        verification.put("missingKeys", 0);
        verification.put("sourceFingerprint", fingerprint);
        verification.put("readbackEvidence", proof.toString());
        var payload = new LinkedHashMap<String, Object>();
        payload.put("sourceComplete", true);
        payload.put("returnedRows", rows);
        payload.put("submittedRows", rows);
        payload.put("responseEvidence", proof.toString());
        payload.put("publicationId", publicationId);
        payload.put("verification", verification);
        return JobDefinitionJson.mapper().writeValueAsString(payload);
    }
}
