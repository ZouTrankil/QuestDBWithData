package com.zoutrankil.data.etf.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.domain.fund.EtfBasic;
import com.zoutrankil.data.domain.fund.EtfBasicKey;
import com.zoutrankil.data.repository.FileEvidenceStore;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.util.List;

/** 当前来源键和保留键用同一完整键编码，值摘要包含原技术时间与全部字段。 */
final class EtfBasicDirectoryEvidence {
    private EtfBasicDirectoryEvidence() {}

    static String key(EtfBasic row) {
        try {
            return JobDefinitionJson.mapper()
                    .writeValueAsString(List.of(row.tsCode(), row.timestamp().toString()));
        } catch (Exception e) {
            throw new IllegalArgumentException("ETF目录完整键无法编码", e);
        }
    }

    static String value(VerifiedBatchExecutor.Codec<EtfBasic, EtfBasicKey> codec, EtfBasic row)
            throws Exception {
        return FileEvidenceStore.sha256(codec.canonicalBytes(row));
    }
}
