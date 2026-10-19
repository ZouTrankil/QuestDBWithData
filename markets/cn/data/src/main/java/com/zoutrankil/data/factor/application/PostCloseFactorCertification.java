package com.zoutrankil.data.factor.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.storage.QuestDbGlobalObservations;
import com.zoutrankil.data.repository.FileEvidenceStore;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** 在独立来源批准、完整键矩阵和实际值回读都通过后，单独出具正式因子凭证。 */
public final class PostCloseFactorCertification {
    @FunctionalInterface public interface SourceGuard {void requireCurrent(JsonNode stock,JsonNode etf)throws Exception;}
    private final QuestDbGlobalObservations observations;private final SourceGuard sources;
    public PostCloseFactorCertification(QuestDbGlobalObservations observations,SourceGuard sources){this.observations=observations;this.sources=sources;}
    public Path certify(Path pipeline,Path approval,String approvalSha,Path output)throws Exception {
        var json=JobDefinitionJson.mapper();byte[] approved=readPinned(approval,approvalSha,16*1024*1024);var authority=json.readTree(approved);
        if(!"java.post_close_factor_source_approval.v1".equals(authority.path("schema").asText())||!authority.path("sourceComplete").isBoolean()||!authority.path("sourceComplete").booleanValue())throw new IllegalStateException("因子来源尚未独立批准完整");
        byte[] original=FileEvidenceStore.readBounded(pipeline,1024*1024,()->new IllegalStateException("盘后回执过大"));var receipt=json.readTree(original);
        if(!"java.post_close_factor_pipeline.v1".equals(receipt.path("schema").asText())||!receipt.path("physicalVisibilityVerified").asBoolean(false)||!"PARTIAL".equals(receipt.path("phase").asText()))throw new IllegalStateException("盘后物理发布未完成");
        LocalDate date=LocalDate.parse(receipt.required("date").asText());if(!date.toString().equals(authority.path("date").asText()))throw new IllegalStateException("来源批准日期不符");
        Path stockReceipt=Path.of(receipt.required("stockReceipt").asText()),etfReceipt=Path.of(receipt.required("etfReceipt").asText());
        var stock=json.readTree(FileEvidenceStore.readBounded(stockReceipt.getParent().resolve("compute.json"),4*1024*1024,()->new IllegalStateException("股票计算回执过大")));
        var etf=json.readTree(FileEvidenceStore.readBounded(etfReceipt.getParent().resolve("panel.json"),64*1024*1024,()->new IllegalStateException("ETF 计算回执过大")));
        Path bundlePath=stockReceipt.getParent().resolve("observations"),etfPath=etfReceipt.getParent().resolve("observations.json");
        var bundle=FactorObservationBundle.read(bundlePath);var etfEvidence=FactorObservationEvidence.read(etfPath);
        if(!bundle.dates().equals(Set.of(date))||!bundle.manifest().inputFingerprint().equals(authority.path("stockInputFingerprint").asText())||!etfEvidence.inputFingerprint().equals(authority.path("etfInputFingerprint").asText())
                ||!bundle.sha256().equals(authority.path("stockBundleSha256").asText())||!etfEvidence.fileSha256().equals(authority.path("etfEvidenceSha256").asText()))throw new IllegalStateException("因子输入或冻结产物与批准版本不同");
        var tableStates=new TreeMap<String,List<JsonNode>>();addStates(tableStates,stock.required("sourceState"));
        for(String field:List.of("sourceState","l2SourceState","stockSourceState","holdingSourceState","technicalSourceState","csiFinancialSourceState","csiHoldingSourceState","csiMarketSourceState","earningsSourceState"))addStates(tableStates,etf.required(field));
        var names=new HashSet<String>();authority.required("sources").fieldNames().forEachRemaining(names::add);if(!names.equals(tableStates.keySet()))throw new IllegalStateException("因子来源批准缺表或多表");
        var evidence=new TreeMap<String,Object>();
        for(var source:tableStates.entrySet()) {
            var expected=authority.path("sources").path(source.getKey());String clocks=FileEvidenceStore.sha256(JobDefinitionJson.canonicalMapper().writeValueAsBytes(json.convertValue(source.getValue(),List.class)));
            if(!clocks.equals(expected.path("sourceStateSha256").asText()))throw new IllegalStateException("因子来源物理版本不符："+source.getKey());
            Path file=approval.toAbsolutePath().getParent().resolve(expected.required("certificate").asText()).normalize();String sha=expected.required("certificateSha256").asText();
            var document=com.zoutrankil.data.service.VerifiedSourceCertificates.validate(readPinned(file,sha,128*1024*1024));
            if(!expected.path("sourceDataset").asText(source.getKey()).equals(document.path("dataset").asText()))throw new IllegalStateException("因子来源凭证归属不符："+source.getKey());
            evidence.put(source.getKey(),Map.of("path",file.toString(),"sha256",sha,"sourceStateSha256",clocks));
        }
        sources.requireCurrent(stock,etf);var before=observations.state();var keys=new TreeSet<String>();long rows=0;
        for(var part:bundle.manifest().parts()) {
            var expected=FactorObservationEvidence.read(bundlePath.resolve(part.file()));if(!expected.fileSha256().equals(part.sha256()))throw new IllegalStateException("股票冻结产物改变");rows+=verify(expected,date,keys);
        }
        rows+=verify(etfEvidence,date,keys);
        if(!digest(keys).equals(authority.path("expectedObservationKeysSha256").asText()))throw new IllegalStateException("因子缺失、多出或错配证券、定义版本或日期");
        sources.requireCurrent(stock,etf);
        if(!before.equals(observations.state())||!bundle.sha256().equals(FactorObservationBundle.read(bundlePath).sha256())||!etfEvidence.fileSha256().equals(FactorObservationEvidence.read(etfPath).fileSha256()))throw new IllegalStateException("认证中来源或输出改变");
        readPinned(approval,approvalSha,16*1024*1024);
        for(var source:evidence.values()){@SuppressWarnings("unchecked") var ref=(Map<String,Object>)source;readPinned(Path.of(ref.get("path").toString()),ref.get("sha256").toString(),128*1024*1024);}
        var proof=new LinkedHashMap<String,Object>();proof.put("schema","java.post_close_factor_certificate.v1");proof.put("date",date);proof.put("sourceComplete",true);proof.put("factorReady",true);proof.put("rows",rows);proof.put("keysSha256",digest(keys));proof.put("physicalState",before);
        proof.put("approval",Map.of("path",approval.toAbsolutePath().normalize().toString(),"sha256",approvalSha));proof.put("sourceCertificates",evidence);proof.put("pipeline",Map.of("path",pipeline.toAbsolutePath().normalize().toString(),"sha256",FileEvidenceStore.sha256(original)));proof.put("stockBundleSha256",bundle.sha256());proof.put("etfEvidenceSha256",etfEvidence.fileSha256());
        Files.createDirectories(output.toAbsolutePath().getParent());FileEvidenceStore.writeNewDurable(output,JobDefinitionJson.mapper().writeValueAsBytes(proof));return output;
    }
    private long verify(FactorObservationEvidence.Snapshot expected,LocalDate date,Set<String> keys)throws Exception {
        if(expected.rows().isEmpty()||expected.rows().stream().anyMatch(row->!row.signalDate().equals(date)))throw new IllegalStateException("因子冻结日期不符或为空");
        if(expected.rows().stream().anyMatch(row->!"complete".equals(row.qualityStatus())||row.factorValue()==null))throw new IllegalStateException("正式因子仍有缺失、无效或不可用值，不能签发 FactorReady");
        for(var row:expected.rows())if(!keys.add(encoded(GlobalObservationCoverage.key(row))))throw new IllegalStateException("股票和 ETF 产物存在重复键");
        for(int from=0;from<expected.rows().size();from+=2000){var page=expected.rows().subList(from,Math.min(from+2000,expected.rows().size()));var delta=GlobalObservationCoverage.compare(page,observations.readKeys(page.stream().map(GlobalObservationCoverage::key).toList()));if(!delta.matchesExpected()||delta.unexpected()!=0)throw new IllegalStateException("正式因子完整值回读不符");}
        return expected.rows().size();
    }
    private static void addStates(Map<String,List<JsonNode>> states,JsonNode rows) {
        if(!rows.isArray()||rows.isEmpty())throw new IllegalStateException("缺冻结来源版本");
        for(var row:rows){String name=row.path("table_name").asText();com.zoutrankil.data.domain.DatasetDefinition.identifier(name);states.computeIfAbsent(name,k->new ArrayList<>()).add(row);}
    }
    private static byte[] readPinned(Path path,String sha,int limit)throws Exception {
        if(!sha.matches("[a-f0-9]{64}")||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("来源批准或原件缺失");
        byte[] bytes=FileEvidenceStore.readBounded(path,limit,()->new IllegalStateException("来源证据过大"));if(!FileEvidenceStore.sha256(bytes).equals(sha))throw new IllegalStateException("来源证据摘要改变");return bytes;
    }
    private static String encoded(GlobalObservationCoverage.Key k)throws Exception{return JobDefinitionJson.canonicalMapper().writeValueAsString(List.of(k.date().toString(),k.entityType(),k.entityId(),k.factorId(),k.version()));}
    static String digest(SortedSet<String> keys)throws Exception {var hash=MessageDigest.getInstance("SHA-256");for(String key:keys){byte[] bytes=key.getBytes(StandardCharsets.UTF_8);hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);}return HexFormat.of().formatHex(hash.digest());}
}
