package com.zoutrankil.data.client.dto;

/** Exact six-field slb_len row; nullable metrics preserve provider nulls. */
public record TushareMarginZrzDto(String tradeDate, String ob, String aucAmount,
        String repoAmount, String repayAmount, String cb) {}
