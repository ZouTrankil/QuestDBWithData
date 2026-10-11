package com.zoutrankil.data.factor.engine;

import java.util.Locale;

/** Ordered Python research categories; first matching rule wins. */
public final class EtfResearchStyle {
    private EtfResearchStyle() {}
    private static final String[][] RULES={
        {"半导体","半导体","芯片","集成电路"},
        {"新能源","新能源","光伏","风电","电池","储能","新能源车"},
        {"医药","医药","医疗","生物","创新药"},
        {"科技","科技","互联网","软件","人工智能","ai","tmt","数字经济","通信","计算机","电子","卫星"},
        {"机器人","机器人","工业母机"},{"成长","成长","创业板成长","科创成长"},
        {"红利","红利","股息","高股息","dividend"},{"价值","价值","value"},
        {"低波","低波","低波动"},{"质量","质量","优质","quality"},
        {"消费","消费","食品","饮料","酒","旅游"},{"金融","金融","银行","证券","券商","保险"},
        {"港股","港股","恒生","h股"},{"化工材料","化工","材料","建材"},
        {"电力设备","电力","电网","电力设备"},{"传媒游戏","传媒","游戏"},
        {"能源资源","煤炭","石油","有色","金属","资源"},
        {"宽基","沪深300","中证500","中证1000","中证2000","中证a500","a500","a50","上证50","创业板etf","创业板50","创业板指","深证100","深100","科创50"}
    };
    public static String classify(String name) {
        String text=name==null?"":name.toLowerCase(Locale.ROOT);
        for(var rule:RULES)for(int i=1;i<rule.length;i++)if(text.contains(rule[i]))return rule[0];
        return "其他";
    }
}
