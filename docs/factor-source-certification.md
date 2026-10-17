# 盘后因子来源认证

原股票/ETF 产物和 partial 回执保留。独立来源批准、完整因子键矩阵及实际值回读都通过后，才生成新的 `java.post_close_factor_certificate.v1` 凭证。

```sh
./gradlew run --args='export-source-certificate --run 真实来源运行ID --output /实际路径/source-certificate.json'
./gradlew run --args='certify-post-close-factor-pipeline --receipt /实际路径/pipeline.json --source-approval /实际路径/approval.json --source-approval-sha256 批准摘要 --output /实际路径/factor-ready.json'
```

来源导出只接受账本中 `VERIFIED`、`VERIFIED_EMPTY` 根运行，固定冻结请求、目标身份和原账本内容。它只证明该请求范围，不代表全市场或整个历史完整。

批准文件为 `java.post_close_factor_source_approval.v1`：

- `date`、`sourceComplete: true`。
- `stockInputFingerprint,etfInputFingerprint,stockBundleSha256,etfEvidenceSha256`，固定审核过的输入和产物。
- `expectedObservationKeysSha256`，来自独立批准的日期、实体类别、实体、因子及定义版本矩阵。
- `sources` 必须覆盖计算声明的全部物理来源。每项含 `certificate,certificateSha256,sourceStateSha256`。来源逻辑名称不同于物理名称时，显式填写 `sourceDataset`；原生 MV 仍需单独核验关联刷新。

来源版本摘要：同一物理表的全部冻结 source-state 条目转为普通列表/Map，用 `JobDefinitionJson.canonicalMapper()` 编码后取 SHA-256。键摘要：字符串数组紧凑 JSON 排序，每项写四字节大端长度和 UTF-8 内容后取 SHA-256。

认证还要求每个正式值的质量为 complete、数值非空。认证重查原账本、来源版本、冻结产物和正式 `factor_observation` 的完整键与值。缺证券、值不符或审核后改变都会停止；不会把 partial 回执改成成功。

`run-post-close-factor-pipeline` 也接受 `--source-approval`、`--source-approval-sha256`，计算后立即认证。缺批准文件时仍返回 partial。隔离 Batch 的 `jdb_test_*` 证书继续用于隔离验收，不能冒充正式库证书。
