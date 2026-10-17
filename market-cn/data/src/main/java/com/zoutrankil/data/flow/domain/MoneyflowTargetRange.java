package com.zoutrankil.data.flow.domain;

import java.time.LocalDate;

public record MoneyflowTargetRange(LocalDate min,LocalDate max,long rows){public MoneyflowTargetRange{if((min==null)!=(max==null)||min!=null&&min.isAfter(max)||rows<0||(min==null)!=(rows==0))throw new IllegalArgumentException("Invalid moneyflow target range/count");}public boolean empty(){return rows==0;}}
