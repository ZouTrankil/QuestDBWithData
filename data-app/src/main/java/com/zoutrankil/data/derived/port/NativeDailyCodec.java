package com.zoutrankil.data.derived.port;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import java.time.*;
import java.util.*;
import com.zoutrankil.data.derived.domain.NativeDailyProjection;
import com.zoutrankil.data.service.VerifiedBatchExecutor;
/** Stateless adapter between the pure record projection and the shared verified executor. */
public final class NativeDailyCodec<R extends Record> implements VerifiedBatchExecutor.Codec<R,Instant> {
    private final NativeDailyProjection<R> projection;
    public NativeDailyCodec(NativeDailyProjection<R> projection){this.projection=Objects.requireNonNull(projection);}
    public Instant key(R row){return projection.key(row);}
    public byte[] canonicalBytes(R row){return projection.canonicalBytes(row);}
    public int estimatedTransportBytes(R row,byte[] canonical){return projection.estimatedTransportBytes(row,canonical);}
}
