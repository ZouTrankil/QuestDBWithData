package com.zoutrankil.data.service;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.stock.mapper.StockDetailInfoMapper;
import com.zoutrankil.data.repository.DatasetWritePreparation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StockDetailStaticWriteContractTest {
    @Test void staticPreparationAcceptsOneFullTypedRowAndRejectsDuplicateBusinessIdentity() {
        var row=new StockDetailInfo("000001.SZ",Instant.parse("2026-09-29T00:00:00Z"),
                "000001","平安银行","主板","SZSE","L",java.time.LocalDate.of(1991,4,3),
                null,null,null,"深圳","银行","CNY",null,"S",null,null);
        var mapper=new StockDetailInfoMapper();var limits=new DatasetWritePreparation.Limits(10000,16*1024*1024);
        var prepared=DatasetWritePreparation.prepareStatic(StockDetailInfoDataset.DEFINITION,
                List.of(row),mapper::values,limits);
        assertEquals(1,prepared.rows().size());
        assertEquals(row,mapper.fromValues(prepared.rows().getFirst()));
        assertThrows(IllegalArgumentException.class,()->DatasetWritePreparation.prepareStatic(
                StockDetailInfoDataset.DEFINITION,List.of(row,row),mapper::values,limits));
        assertThrows(IllegalArgumentException.class,()->DatasetWritePreparation.prepare(
                StockDetailInfoDataset.DEFINITION,List.of(row),mapper::values,limits));
    }
}
