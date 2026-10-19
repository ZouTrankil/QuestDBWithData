package com.zoutrankil.data.index.port;

import com.zoutrankil.data.index.domain.IndexMembershipState.*;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Creates a fresh owned stage and returns its verified physical evidence. */
public interface IndexMembershipStagingPort {
    Verified write(Prepared prepared,Path evidence,BooleanSupplier cancelled) throws Exception;
}
