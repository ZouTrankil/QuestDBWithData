package com.zoutrankil.questdbwithdata.client.dto;

/** Source hierarchy codes are retained even though legacy persistence stores hierarchy names only. */
public record TushareIndexMembershipDto(String l1Code,String l1Name,String l2Code,String l2Name,
        String l3Code,String l3Name,String tsCode,String name,String inDate,String outDate,String isNew) {}
