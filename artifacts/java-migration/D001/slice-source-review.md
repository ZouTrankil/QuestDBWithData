# D001 finite slices and coverage

Status: in progress; no new source or target write validation claimed.

ExchangeCalendarSlices splits an explicit finite range (at most 3660 days per exchange) at year boundaries. Initial admission is SSE/SZSE. Incremental planning takes per-exchange verified coverage and rereads a declared 1..366-day revision overlap, clipped only by the explicit bootstrap lower bound. A checkpoint beyond the frozen request end is rejected for explicit reconciliation. A raw target MAX(date) is not accepted as proof by this interface; deriving verified coverage remains the runner owner's responsibility.

Each source slice must contain exactly one row for every calendar day, including closed days. Missing, empty, duplicate, wrong-exchange and out-of-range responses fail before any batch is handed to a writer. Leap years and year boundaries are covered.

ExchangeCalendarSource uses the existing TusharePageService for bounded, cancellable requests with shared HTTP/retry/rate controls. Requests include exchange/start_date/end_date and no offset. A 367-row local response budget detects overflow for a maximum 366-day slice; exact day coverage supplies the independent completeness gate. Captured raw responses have durable evidence paths and SHA-256 fingerprints. Source values are mapped strictly, without numeric/text coercion.

`local-D001-slices-19e6` and `local-D001-source-compile-d922` pass the six mapping/slice tests. The latter compiles the source adapter but does not call Tushare or exercise its live permissions. The final owner still needs to enforce the effective trade_cal rate at or below the Python-declared 20/minute (and any stricter account policy), register the job, derive verified checkpoints, integrate typed reads/writes and run real source-write-read/idempotency acceptance.
