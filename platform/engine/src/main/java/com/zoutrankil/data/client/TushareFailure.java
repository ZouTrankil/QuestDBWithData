package com.zoutrankil.data.client;

import java.io.IOException;
import java.time.Duration;

/** 可安全记录的错误：不含来源响应体、请求参数、凭据或底层传输异常消息。 */
public final class TushareFailure extends IOException {
    public enum Kind {
        HTTP,
        BUSINESS,
        BUSINESS_RATE_LIMIT,
        CONTRACT,
        TRANSPORT
    }

    private final Kind kind;
    private final Integer code;
    private final Duration retryAfter;

    public TushareFailure(Kind kind, Integer code, String safeMessage) {
        this(kind, code, safeMessage, null);
    }

    public TushareFailure(Kind kind, Integer code, String safeMessage, Duration retryAfter) {
        super(safeMessage);
        this.kind = kind;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public Integer code() {
        return code;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
