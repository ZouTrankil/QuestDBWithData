package com.zoutrankil.data.stock.port;
import com.zoutrankil.data.domain.DailyMarketBar;
import com.zoutrankil.data.sync.port.DateSliceReadPort;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;
public interface DailyWriteSession extends VerifiedWriteSession<DailyMarketBar, DailyMarketBar.Key>, DateSliceReadPort<DailyMarketBar> {}
