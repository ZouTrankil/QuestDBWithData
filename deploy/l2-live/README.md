# Java live Level2 subscription

This process directly consumes the existing vendor MQTT feed and runs no trading actions. It uses three topics per symbol: `trans/<code>`, `order/<code>`, and `market/<code>`. The current limit is 16 SH/SZ symbols and 48 topics. The provider requires client ID to equal username and allows one terminal per account. Stop the previous collector through its runtime control before taking over that account.

Configure `l2.username` and `l2.password` in the `l2` profile of the shared `config/application.yml`; the collector automatically selects this profile and inherits the shared QuestDB endpoint. See [configuration usage](../../docs/configuration.md). Existing `var/l2-live/credentials.env` files are still accepted as literal overrides, followed by process environment variables. The collector does not print credentials.

```powershell
./deploy/l2-live/run.ps1 -Action plan
./deploy/l2-live/run.ps1 -Action start
./deploy/l2-live/run.ps1 -Action status
./deploy/l2-live/run.ps1 -Action stop
```

`start` builds from the local Gradle cache and launches a hidden Java process. Each launch uses a unique argfile and a fixed runtime jar snapshot under `var/l2-live/runtime/<id>/lib`, with SHA256 hashes in its manifest; later Gradle builds do not replace the running classpath. If credentials are missing, it waits in `awaiting_credentials` and reloads the file. Configure the file to activate the authorized subscription. Starting a process is not evidence of successful subscription: inspect `collector.ready`, all-topic acknowledgements, advancing receive counts and verified event rows.

Default symbols come from unexpired `force_symbols` in the existing Python override file, filtered by allow/block policy. Allow entries alone do not create subscriptions. An explicit symbol list is also supported:

```powershell
./deploy/l2-live/run.ps1 -Action start -Symbols '563360.SH'
```

A running process freezes its startup symbol list. Apply a changed subscription scope by graceful stop and start. No expired override is extended automatically. Private credentials are necessary to activate a real subscription; this implementation does not purchase a provider account.

Raw bytes and exact receive instants are committed to the SQLite journal before normalization. A fixed prepared batch contains events, minute observations and the accumulator checkpoint. Both QuestDB targets must pass schema, identity, WAL and exact readback checks before that batch is completed. An uncertain write remains pending and is reconciled from the same frozen values; it is not blindly posted again. During database unavailability, raw reception continues within both an 8 GiB raw-byte bound and an 8 GiB SQLite page bound; stored outbox/checkpoint data also uses that page bound. There is no automatic retention deletion or disk-space reservation.

Within the admitted 1 MiB raw-envelope bound, protocol frames above 64 KiB are audited as `oversized_wire_payload`: their exact bytes remain in the journal/export, while QuestDB audit rows retain the capture ID/hash instead of inline large Base64. The raw envelope records whether all SUBACKs were confirmed when the message arrived. Unconfirmed subscription, scope/QoS rejection and `aggregation_overflow` produce invalid audit events and never enter minute aggregation.

The new outputs are `l2_live_events_v2` and `l2_live_micro_state_1m_v2`. They preserve the legacy tables, retain all ten levels, use explicit UTC intraday instants and record the parser version and local capture identity. Minute outputs are partial observations available at their processing time, rather than future-informed whole-day features. Existing readers of legacy live tables need an explicit adapter before consuming these versioned outputs.

MQTT QoS 0 cannot recover messages never received during a disconnection. Local raw replay recovers committed captures only. Vendor sequence scope remains unverified; the implementation does not assume per-symbol continuous sequence numbers or hash away identical trade payloads.

```powershell
./deploy/l2-live/run.ps1 -Action replay
./deploy/l2-live/run.ps1 -Action export
```

Replay requires the live process to be stopped and publishes pending frozen captures through the same verification path. Export writes an immutable raw JSONL snapshot and reports its hash. Stop uses `stop.request` and waits for graceful draining; it does not kill an unrelated process.
