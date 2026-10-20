package com.zoutrankil.data.cli;

import com.zoutrankil.data.domain.temporal.MarketTime;
import com.zoutrankil.data.factor.application.L2FactorPanelComputation;
import com.zoutrankil.market.cn.time.CnMarketTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** CLI presentation for shared native L2 computation. */
@Component
@ConditionalOnNotWebApplication
public final class L2FactorPanelCommands implements CliCommandFamily {
    private final L2FactorPanelComputation computation;

    public L2FactorPanelCommands(JdbcTemplate jdbc) {
        this(jdbc, CnMarketTime.system());
    }

    @Autowired
    public L2FactorPanelCommands(JdbcTemplate jdbc, MarketTime marketTime) {
        computation = new L2FactorPanelComputation(jdbc, marketTime);
    }

    @Override
    public Set<String> commands() {
        return Set.of("compute-l2-factor-panel");
    }

    @Override
    public void execute(String command, Map<String, String> options) throws Exception {
        if (!commands().contains(command))
            throw new IllegalArgumentException("Unsupported L2 panel command");
        CliOutput.printJson(computation.compute(options), CliOutput.Profile.JOB_DEFINITION, true);
    }
}
