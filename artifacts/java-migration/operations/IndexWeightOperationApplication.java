package local.market;

import com.zoutrankil.questdbwithdata.QuestDbWithDataApplication;
import com.zoutrankil.questdbwithdata.config.QuestDbProperties;
import com.zoutrankil.questdbwithdata.config.TushareProperties;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/** Isolates current market acceptance from other agents' unfinished component registrations. */
@Configuration
@EnableAutoConfiguration
@EnableConfigurationProperties({QuestDbProperties.class,TushareProperties.class})
@ComponentScan(basePackages="com.zoutrankil.questdbwithdata", excludeFilters={
    @ComponentScan.Filter(type=FilterType.ASSIGNABLE_TYPE,classes=QuestDbWithDataApplication.class),
    @ComponentScan.Filter(type=FilterType.REGEX,pattern=".*(L2|IndexDailyMarket|IndexDailyBasic|IndexMonthly|DcIndex|StockSuspend|StockSt|EtfBasic|EtfDaily|EtfAdj|EtfFactor|EtfShare|EtfPortfolio).*" )
})
public class IndexWeightOperationApplication {}
