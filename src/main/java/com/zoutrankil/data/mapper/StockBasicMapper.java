package com.zoutrankil.data.mapper;

import com.zoutrankil.data.client.dto.TushareStockBasicDto;
import com.zoutrankil.data.domain.StockBasic;
import com.zoutrankil.data.domain.temporal.TemporalValues;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@Component
public class StockBasicMapper {

    public StockBasic toDomain(TushareStockBasicDto source) throws IOException {
        return new StockBasic(
                source.tsCode(),
                source.symbol(),
                source.name(),
                source.area(),
                source.industry(),
                parseListDate(source.listDate()));
    }

    private static LocalDate parseListDate(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return TemporalValues.businessDate(value, TemporalValues.DateFormat.BASIC);
        } catch (DateTimeParseException exception) {
            throw new IOException("Tushare returned an invalid list_date: " + value, exception);
        }
    }
}
