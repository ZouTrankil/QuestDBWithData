package com.zoutrankil.questdbwithdata.client.dto;

/** Seven provider fields for one current THS board constituent. */
public record TushareThsMemberDto(String tsCode, String constituentCode, String constituentName,
        Double weight, String inDate, String outDate, String isNew) {}
