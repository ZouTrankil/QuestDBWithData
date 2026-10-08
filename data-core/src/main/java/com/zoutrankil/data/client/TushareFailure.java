package com.zoutrankil.data.client;

import java.io.IOException;

/** Safe to log: no source body, request parameters, credentials or nested transport message. */
public final class TushareFailure extends IOException {
    public enum Kind { HTTP, BUSINESS, BUSINESS_RATE_LIMIT, CONTRACT, TRANSPORT }
    private final Kind kind;
    private final Integer code;
    private final java.time.Duration retryAfter;
    public TushareFailure(Kind kind, Integer code, String safeMessage) {
        this(kind, code, safeMessage, null);
    }
    public TushareFailure(Kind kind, Integer code, String safeMessage, java.time.Duration retryAfter) {
        super(safeMessage);
        this.kind = kind;
        this.code = code;
        this.retryAfter = retryAfter;
    }
    public Kind kind() { return kind; }
    public Integer code() { return code; }
    public java.time.Duration retryAfter() { return retryAfter; }
}
