package com.zoutrankil.data.stock.port;

import com.zoutrankil.data.sync.port.DateSliceReadPort;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

public interface StockDateWriteSession<T, K> extends VerifiedWriteSession<T, K>, DateSliceReadPort<T> {}
