# D088/D089 synthetic compatibility oracle

Captured before extraction using preserved original Java main/test class bytes, Java 24.0.2+12 (Eclipse Adoptium), Jackson 2.21.5. Inputs come from the original offline mapping tests' `sourceRow()` fixtures plus named mutations. The generator invoked only definitions, mappers, static CODEC and private page fingerprint functions. No Python reader, Parquet artifact, database, network or ledger was used.

`original-goldens.json` SHA256: `1d8a7a5c190d64add12ba00bc374e4d7775edd8ef562329ef9c2db7f63caff6a`. Actual original class locations and byte hashes are embedded. Six page frames were independently assembled with PowerShell/.NET using the documented version/NUL/source/NUL/cursor/int32BE-length/row framing; every resulting hash equals the original implementation.

These establish code compatibility with synthetic inputs. They are not external source receipts or evidence of real source coverage. Generator, copied original classes/source and capture scripts are retained under `.gradle/refactor-baseline/T15-l2` in the local ignored evidence directory.
