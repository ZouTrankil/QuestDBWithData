package com.zoutrankil.questdbwithdata.client.dto;

/** Original text columns from the documented 17-column catalog file. */
public record IndexCatalogSourceRow(String code,String shortName,String fullName,String baseDate,String basePoint,
        String series,String sampleCount,String referenceClose,String oneMonthReturnPercent,String assetClass,
        String hotspot,String currency,String cooperationFlag,String trackingProductFlag,String complianceStatus,
        String category,String publicationDate) {}
