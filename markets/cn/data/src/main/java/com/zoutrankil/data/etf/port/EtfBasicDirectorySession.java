package com.zoutrankil.data.etf.port;

import com.zoutrankil.data.domain.fund.EtfBasic;
import com.zoutrankil.data.domain.fund.EtfBasicKey;
import com.zoutrankil.data.sync.port.VerifiedWriteSession;

import java.util.List;

/** 同一物理目录的完整有界回读；旧目录行与本次来源行分别核验。 */
public interface EtfBasicDirectorySession extends VerifiedWriteSession<EtfBasic, EtfBasicKey> {
    List<EtfBasic> readDirectory();
}
