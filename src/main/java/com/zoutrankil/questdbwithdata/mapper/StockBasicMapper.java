package com.zoutrankil.questdbwithdata.mapper;

import com.zoutrankil.questdbwithdata.client.dto.TushareStockBasicDto;
import com.zoutrankil.questdbwithdata.domain.StockBasic;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Component
public class StockBasicMapper {
    private static final DateTimeFormatter TUSHARE_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

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
            return LocalDate.parse(value, TUSHARE_DATE_FORMAT);
        } catch (DateTimeParseException exception) {
            throw new IOException("Tushare returned an invalid list_date: " + value, exception);
        }
    }
}
