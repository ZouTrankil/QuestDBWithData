package com.zoutrankil.data.domain;

/** Implemented by the owning adapter, so definitions are registered with actual code. */
public interface DatasetImplementation {
    DatasetDefinition definition();
}
