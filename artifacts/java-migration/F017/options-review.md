# F017 CLI input boundary

Status: in progress, not verified. Predecessor F016 is verified with pending human review.

The shared option parser now rejects duplicate flags (including mixed `--key value` and `--key=value` forms), blank values and malformed option names before invoking any source, write or schedule service. Previously later flags silently replaced earlier parameters; this could change an intended sync scope or enable flag.

`local-F017-options-416b` passed 10 CLI tests, zero failures or skips. The new boundary test asserts no runner or legacy writer interaction for ambiguous codes, duplicate write request files, conflicting schedule enable flags and empty/malformed inputs. Existing read/write/job/schedule CLI routing tests also pass.

Still required by F017: unified list/show/validate/plan/run/status/history/cancel/resume behavior, non-writing planning, structured output and stable exit semantics. This parser change alone does not complete F017 or establish additional external-data acceptance.
