package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.derived.domain.NativeDailyProjection;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import java.time.Instant;
import java.util.Objects;

/** Stateless adapter between the pure record projection and the shared verified executor. */
public final class NativeDailyCodec<R extends Record>
        implements VerifiedBatchExecutor.Codec<R, Instant> {
    private final NativeDailyProjection<R> projection;

    public NativeDailyCodec(NativeDailyProjection<R> projection) {
        this.projection = Objects.requireNonNull(projection);
    }

    public Instant key(R row) {
        return projection.key(row);
    }

    public byte[] canonicalBytes(R row) {
        return projection.canonicalBytes(row);
    }

    public int estimatedTransportBytes(R row, byte[] canonical) {
        return projection.estimatedTransportBytes(row, canonical);
    }
}
