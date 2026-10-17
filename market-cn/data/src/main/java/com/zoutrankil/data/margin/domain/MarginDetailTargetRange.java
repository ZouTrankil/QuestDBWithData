package com.zoutrankil.data.margin.domain;

import java.time.LocalDate;

public record MarginDetailTargetRange(LocalDate min,LocalDate max,long rows){public MarginDetailTargetRange{if((min==null)!=(max==null)||min!=null&&min.isAfter(max)||rows<0||(min==null)!=(rows==0))throw new IllegalArgumentException("Invalid D029 physical range/count");}public boolean empty(){return rows==0;}}
