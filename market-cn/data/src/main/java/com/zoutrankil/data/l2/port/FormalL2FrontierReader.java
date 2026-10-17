package com.zoutrankil.data.l2.port;

import com.zoutrankil.data.l2.domain.FormalL2Frontier;

@FunctionalInterface
public interface FormalL2FrontierReader {
    FormalL2Frontier read() throws Exception;
}
