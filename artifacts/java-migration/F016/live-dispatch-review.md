# F016 real scheduled dispatch and command review

The opt-in `ScheduleDispatchLiveTest` passed in `local-F016-dispatch-live-d251`.
Evidence directory: `21766a9c38434835abd627e069706fcd` under this directory.

A fixed clock triggers a single bounded stock_basic request through SyncScheduleManager and the existing SyncJobRunner. The actual Tushare response contains one row. QWP writes that row to an owned isolated QuestDB DAY/WAL/dedup table, then all seven stored fields are compared by SELECT. The schedule history links the verified runner ID. Its Asia/Shanghai logical date is 2026-09-29 although the due instant is on the preceding UTC date. Reopening the control database and repeating the tick does not dispatch again. The successfully verified isolated table was dropped.

`tools/verify_schedule_evidence.py` separately compares the original source response with captured actual SQL values: one matched row, zero missing, mismatched or duplicate keys. This is snapshot-only functional acceptance; it does not establish historical incremental support for stock_basic.

Full regression `local-F016-full-640e`: 170 tests, 151 passed, 19 external-condition skips, zero failures/errors. The live test was run separately. Subsequent `local-F016-routing-f71c` validates formal job and group service routing, retained run IDs, CLI explicit parameters, and non-success exit for IN_DOUBT. Routing tests use controlled services; they are not additional live writes.

## Usage

Pass these arguments to the existing application CLI:

```text
schedule-put --request artifacts/java-migration/F016/schedule-example.json
schedule-status --id stock.sample.review
schedule-enable --id stock.sample.review --enabled false
schedule-tick
```

The example is disabled. Import and status do not execute data requests. `schedule-tick` explicitly evaluates enabled schedules and can perform writes through their existing runners. No background timer is installed. Manual explicit scheduling does not change a job's dailyEligible catalog flag. Exchange-session scheduling is rejected by the sample service until an authoritative calendar owner is admitted in subsequent data tasks.

New interval scheduling exposed a separate scan-bound issue: a 370-slot scan can select an old minute slot instead of the latest due one. The scan now covers the allowed seven-day window at the minimum one-minute interval, and a dedicated regression checks the latest slot. F016 completion registration remains pending final review of the current implementation.
