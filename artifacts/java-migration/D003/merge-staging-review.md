# D003 content merge and monthly-WAL staging

`IndexCatalogStorage` reads a complete bounded physical snapshot, checks the exact 18-column/WAL/MONTH/no-dedup contract and WAL convergence, requires unique business codes and preserves all raw stored values. It rejects more than 5,000 rows or 8 MiB and rechecks physical identity after reading.

`IndexCatalogMerge` compares all business values except import_time. An unchanged source observation retains the stored row and timestamp; missing source codes remain in the target. Revisions use the explicitly selected file, not numeric ordering of ingestion clocks. The result records inserted, revised, unchanged and retained-absent counts. This is not a claim that file prices are current or newer market observations.

`local-D003-merge-readonly-84db` passed two merge tests and one live read-only test. The actual 2,274-row target and 2,343-row CSV merge to 2,803 planned rows: 529 inserts, 1,814 revisions and 460 retained absent codes. Repeating the exact source against that planned result produces zero writes and preserves all timestamps. The real production snapshot stayed unchanged. Evidence: merge-readonly.json.

`IndexCatalogStaging` creates only a uniquely owned temporary monthly WAL table. Before DDL it saves complete intended rows, and it validates and packs inserts into at most 250 rows and 256 KiB estimated application payload per batch. It checks cancellation between batches, waits at most 20 seconds for WAL convergence, and compares all 18 physical fields after reading the complete stage. Failed/uncertain stages remain for inspection; no automatic retry appends to them. Empty or unchanged merges reject staging.

`local-D003-staging-58b2` passed the real-file/live-QuestDB test: all 2,803 merged rows were written in bounded batches and exactly read back. Repeating the file against the stage produced no changes and refused another staging write. The production table's identity and all rows stayed unchanged. Evidence: stage-ce89eb73-5367-4c31-9591-7baf79c7889a/stage-readback.json plus the stage intent and full verified snapshot. The successful owned stage was dropped after saving evidence.

D003 remains running. A verified isolated stage is not a published target or a completed sync job. WAL publication/identity handling, durable job/slice management, write-group routing and cancellation/recovery integration still require implementation and acceptance.
