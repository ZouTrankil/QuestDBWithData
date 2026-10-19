package com.zoutrankil.data.sync.port;

import java.time.LocalDate;
import java.util.List;

/** Reads the dates and rows covered by a dataset's business-date slices. */
public interface DateSliceReadPort<T> {
    List<LocalDate> readExistingDates();
    List<T> readDate(LocalDate date);
}
