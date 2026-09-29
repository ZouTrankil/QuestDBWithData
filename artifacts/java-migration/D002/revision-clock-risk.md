# D002 merge review: observation clocks

The initial `StockDetailInfoMerge.merge` compared `row.observedAt()` with an existing row's `observedAt()` before accepting changed business fields. This could reject a valid source correction when the existing row came from Python's legacy naive-local-clock-as-UTC path and the new Java row uses an actual UTC instant. The stored legacy clock can be ahead of real time by the host's UTC offset, while neither timestamp is an upstream business revision.

The merge now accepts a changed business value without comparing those observation clocks, while a same-value row remains unchanged without refreshing its observation time. The updated regression case covers a changed row whose clock is numerically earlier than the stored one. This still requires serialized ownership, target preflight, and exact post-publication readback in the owner before actual data acceptance; no test of those requirements is claimed by this note.

`local-D002-legacy-clock-fix`: `StockDetailInfoMergeTest` 3 passed, 0 failed, 0 skipped (isolated Gradle report under `var/local-D002-legacy-clock-fix-build/test-results/test`).
