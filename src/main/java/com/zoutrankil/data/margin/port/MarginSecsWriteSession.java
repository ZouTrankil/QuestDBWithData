package com.zoutrankil.data.margin.port;

import com.zoutrankil.data.domain.MarginSecs;
import com.zoutrankil.data.domain.MarginSecsKey;
import com.zoutrankil.data.margin.domain.MarginSecsState.TargetRange;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
import java.time.LocalDate;
import java.util.List;

/** Per-run writer, preserving distinct before-write and ACK-readback checks. */
public interface MarginSecsWriteSession extends VerifiedWriteSession<MarginSecs, MarginSecsKey> {
    String table();
    String targetId();
    List<MarginSecs> readDateBefore(LocalDate date);
    List<MarginSecs> readDate(LocalDate date);
    TargetRange readTargetRange();
}
