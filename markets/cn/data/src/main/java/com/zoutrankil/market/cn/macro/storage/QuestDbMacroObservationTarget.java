package com.zoutrankil.market.cn.macro.storage;

import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.market.cn.macro.port.MacroObservationTarget;
import com.zoutrankil.market.cn.observation.storage.QuestDbDateObservationTarget;

import io.questdb.client.QuestDB;

import org.springframework.jdbc.core.JdbcTemplate;

/** 宏观只复用标量发送和全字段回读；统计期完整性仍由宏观 owner 核验。 */
public final class QuestDbMacroObservationTarget extends QuestDbDateObservationTarget
        implements MacroObservationTarget {
    public QuestDbMacroObservationTarget(
            DatasetDefinition definition, JdbcTemplate jdbc, QuestDB questdb) {
        super(definition, jdbc, questdb);
    }
}
