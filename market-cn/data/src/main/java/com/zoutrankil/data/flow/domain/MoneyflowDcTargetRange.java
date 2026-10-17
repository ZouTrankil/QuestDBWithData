package com.zoutrankil.data.flow.domain;

import java.time.LocalDate;

public record MoneyflowDcTargetRange(LocalDate min,LocalDate max,long rows){public MoneyflowDcTargetRange{if((min==null)!=(max==null)||min!=null&&min.isAfter(max)||rows<0||(min==null)!=(rows==0))throw new IllegalArgumentException("Invalid moneyflow_dc target range/count");}public boolean empty(){return rows==0;}}
