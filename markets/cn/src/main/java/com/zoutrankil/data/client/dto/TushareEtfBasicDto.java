package com.zoutrankil.data.client.dto;

/** Raw fund_basic(market=E) row. Technical QuestDB columns are deliberately absent. */
public record TushareEtfBasicDto(
        String tsCode,
        String name,
        String management,
        String custodian,
        String fundType,
        String foundDate,
        String dueDate,
        String listDate,
        String issueDate,
        String delistDate,
        Double issueAmount,
        Double managementFee,
        Double custodianFee,
        Double durationYear,
        Double parValue,
        Double minimumAmount,
        Double expectedReturn,
        String benchmark,
        String status,
        String investType,
        String type,
        String trustee,
        String purchaseStartDate,
        String redemptionStartDate,
        String market) {}
