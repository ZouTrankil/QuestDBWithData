# D002 database-bound target identity

Static target identities now use `static-v2-` plus a SHA-256 digest of the PGWire endpoint, database, table name, physical table ID and directory. The endpoint comes from the active JDBC connection metadata. Authentication query parameters are excluded; neither the URL nor credentials are saved in the identity or parsing errors. This distinguishes configured endpoints; it is not an attestation of a server hidden behind a reconfigured proxy or DNS name.

Run creation, current-target checks, completed-child revalidation and uncertain-run recovery use the same identity function. Completed-child revalidation also requires the exact physical successor identity recorded in the receipt, in addition to all rows and the fingerprint. Old `static-<table>-<id>` receipts are not silently upgraded or accepted as evidence of an endpoint binding they never recorded. The earlier observed failure was already recovered before this change; its historical evidence remains intact.

Validation:

- `local-D002-target-binding-f831`: two identity boundary tests and one real QuestDB owner failure-boundary test passed. Different host, port, database or physical identity changes the token; credential rotation does not. Invalid endpoint parsing does not echo credentials.
- `local-D002-target-owner-b169`: real-source owner live test passed. First insert, unchanged repetition and incremental second-code insertion all complete with verified QuestDB readback. The first run's receipt revalidates after the unchanged repetition, but is rejected after a subsequent publication changes the physical successor. The successful isolated target and its backups were dropped after verification.

D002 remains running until all remaining task acceptance requirements are reconciled in its result and completion register.
