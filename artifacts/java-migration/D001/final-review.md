# D001 final acceptance

Status: verified for the bounded local migration task; human review remains pending_review. Earlier progress reviews describe their observation time and are superseded by this final matrix.

| Requirement | Implementation and evidence |
| --- | --- |
| D01 field semantics | ExchangeCalendar, TushareTradeCalendarDto, mapper and DatasetDefinition; strict LocalDate/UTC-midnight carrier and prior-date/flag checks; mapping tests and actual 30-row baseline |
| D02 identity/revisions | Complete exchange+calendar_date key maps to physical exchange+cal_date; repeated isolated source writes retain exact values and unique keys |
| D03 physical schema | YEAR partition, designated cal_date, WAL and dedup verified; exact preflight rejects drift; physical-ddl.txt and wal-preflight.json; existing columns retained without production DDL |
| D04 reads | Registered repository and projection-compatible read group; real two-exchange keyset pagination compared with explicit physical SQL; local-D001-read-projection-48d3 passed 11 tests |
| D05 writes | Typed calendar write port, prepared-write group admission, exact full-key/full-value readback and WAL gate; real source-derived four-row group rerun passes |
| D06 source | Real trade_cal requests bounded by exchange/year, strict complete daily coverage and saved response fingerprints; no all-history fetch or weekday substitute |
| D07 management | Registered data.exchange_calendar v1, bounded plan/run/resume CLI, shared status/history/cancellation; actual process plan preflight and catalog startup tests |
| D08 faults/recovery | Shared rate/retry path, trade_cal ceiling 20/min preserving observed 10/min setting; missing/duplicate/bad/empty source rejects; cancellation no pull/write; unknown submission held across ledger reopen; completed group child reread before reuse |
| D09 actual data acceptance | Real owner first four rows, reopen ledger and actual coverage, overlapping next eight rows, both exchange checkpoints 09-26→09-28; all eight source rows independently match actual QuestDB SQL |

Canonical owner evidence is `owner-f9a4ed4db14a447e9e1e0ce2a0877b92/`: owner-readback.json, retained first/second source receipts, immutable run records and independent-values.json. SQL evidence has zero missing, duplicate or mismatched keys across all four fields. The second run submitted eight rows; inserted/updated/unchanged counts are not inferred from QWP acknowledgements. Logical date for these owner runs is 2026-09-28, while acceptance was performed September 29.

`local-D001-final-suite-832b` completed successfully after fixing an outdated catalog test that assumed no daily jobs. The precise total/pass/conditional-skip counts are in results/D001.json. Separate opt-in live source, read, write, owner, sync-group and prepared-write-group runs supply actual data evidence; skipped live tests in the standard suite are not counted as passed data checks.

Limits: no naturally occurring source revision was observed; overlap refetch/new-day additions and idempotence were tested. Mixed stock/calendar compositions are wired but were not live-tested together. No production table was written or altered and no background schedule was enabled. Test-owned tables were removed after success, with raw source and query results retained for human comparison. Unknown transport outcomes are not automatically replayed. SSE/SZSE are the admitted exchanges, matching the Python connector scope.

See usage-and-mapping.md for names, bounded commands, current compatibility rules, Python lookup paths and the manual comparison checklist. D002 remains the next separate data task; no work for that dataset is counted here.
