# D001 real source and effective rate validation

`local-D001-source-live-727b` passed the opt-in live source test and rate-policy test. Evidence: `source-84aebc9e-ed57-4d0a-88d9-524e7c52b9a3/source-validation.json` and its two original response files.

Two bounded trade_cal requests covered SSE and SZSE, 2026-09-25 through 2026-09-28 inclusive: eight source rows, zero writes. Each slice passed exact calendar-day coverage and strict field mapping. The actual response marks September 25, 26 and 27 closed and September 28 open, with previous trade date September 24. The original test's assumption of two closed weekend days was wrong; it was corrected to verify actual source-provided open/closed coverage without a weekday approximation. The failed first test and response remain historical evidence, not accepted completion.

ClientConfiguration now supplies effective endpoint limits from TushareProperties. trade_cal is capped at 20/minute, preserving a stricter configured endpoint or default limit. The actual live context used 10/minute, with the existing credential-wide/account budget and retry path unchanged. Unit checks prevent broader defaults or overrides from raising the endpoint above 20.

ExchangeCalendarSourceTest exercises the real PageExecutor around controlled responses: empty/missing/duplicate/out-of-range/invalid-flag rows are rejected, provider failure stays Incomplete with zero consumed rows, cancellation prevents the fetcher call, and valid reversed rows normalize to date order. Provider failures are deliberately wrapped by PageExecutor's existing Incomplete contract; the test was adjusted to that public contract rather than expecting a raw IOException.

No QuestDB business write has been performed for D001. Remaining acceptance includes the real writer, per-exchange verified checkpoint derivation, revision overlap, read/write group admission and source-to-QuestDB exact-value/idempotency checks.
