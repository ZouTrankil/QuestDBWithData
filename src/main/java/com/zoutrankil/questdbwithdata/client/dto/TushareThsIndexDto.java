package com.zoutrankil.questdbwithdata.client.dto;

/** The six explicitly requested provider fields; observation time is supplied by the owning job. */
public record TushareThsIndexDto(String tsCode,String name,Integer count,String exchange,String listDate,String type) {}
