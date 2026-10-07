package com.zoutrankil.batch;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/** Scalar rules shared by explicitly registered row policies. */
enum SourceValuePolicy {
    STANDARD, QUOTED_DECIMALS, MONEYFLOW, MONTH, MAIN_BUSINESS, DISCLOSURE, SHARE_FLOAT, AUDIT;

    void beforeRow(LocalDate date) {
        if (this == MAIN_BUSINESS && (!Set.of(3,6,9,12).contains(date.getMonthValue()) || date.getDayOfMonth()!=date.lengthOfMonth()))
            throw new IllegalArgumentException("fina_mainbz report date must be a quarter end");
        if (this == DISCLOSURE && (!Set.of(3,6,9,12).contains(date.getMonthValue()) || date.getDayOfMonth()!=date.lengthOfMonth()))
            throw new IllegalArgumentException("disclosure_date period must be a quarter end");
    }
    Object nullValue(SourceContract.Column column) {
        return this == MONEYFLOW && column.type().equals("LONG") ? 0L : null;
    }
    Object convert(SourceContract contract, SourceContract.Column column, JsonNode value, LocalDate date) {
        return switch (column.type()) {
            case "TIMESTAMP", "TIMESTAMP_NS" -> {
                if (this == MONTH && column.source().equals("month")) {
                    if(!value.isTextual() || !value.asText().matches("[0-9]{6}")) throw new IllegalArgumentException("Exact CPI observation month required");
                    YearMonth month=YearMonth.parse(value.asText(),SourceContract.BASIC_MONTH);
                    if(!month.equals(YearMonth.from(date))) throw new IllegalArgumentException("CPI returned a different observation month");
                    yield month.atDay(1).toString();
                }
                if(!value.isTextual() || !value.asText().matches("[0-9]{8}")) throw new IllegalArgumentException("Exact business date required");
                LocalDate parsed=LocalDate.parse(value.asText(),DateTimeFormatter.BASIC_ISO_DATE);
                if(column.source().equals(contract.sourceDate()) && !parsed.equals(date)) throw new IllegalArgumentException("Source returned a different logical date");
                if(!column.source().equals(contract.sourceDate()) && parsed.isAfter(date) && this != SHARE_FLOAT && this != DISCLOSURE)
                    throw new IllegalArgumentException("Report period cannot follow publication date");
                if(this == SHARE_FLOAT && column.source().equals("float_date") && parsed.isBefore(date))
                    throw new IllegalArgumentException("Unlock event date cannot precede its announcement date");
                yield parsed.toString();
            }
            case "SYMBOL", "STRING" -> {
                if(!value.isTextual()) throw new IllegalArgumentException("Text type mismatch");
                if(column.key() && (value.asText().isBlank() || value.asText().length()>128)) throw new IllegalArgumentException("Invalid text key");
                yield value.asText();
            }
            case "DOUBLE" -> {
                double number;
                if((this == QUOTED_DECIMALS || this == MONTH || this == DISCLOSURE) && value.isTextual()
                        && value.asText().trim().matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
                    number=Double.parseDouble(value.asText().trim());
                else if(value.isNumber()) number=value.doubleValue();
                else throw new IllegalArgumentException("Numeric type mismatch");
                if(!Double.isFinite(number)) throw new IllegalArgumentException("Finite numeric value required");
                yield number;
            }
            case "LONG", "INT" -> {
                if(!value.isIntegralNumber() || !value.canConvertToLong()
                        || column.type().equals("INT")&&!value.canConvertToInt()) throw new IllegalArgumentException("Integer precision mismatch");
                yield value.longValue();
            }
            default -> throw new IllegalStateException("Unsupported type");
        };
    }
    void afterValue(SourceContract.Column column, JsonNode value, LocalDate date) {
        if(this == SHARE_FLOAT && column.source().equals("ann_date")) {
            if(!value.isTextual() || !value.asText().matches("[0-9]{8}")
                    || !LocalDate.parse(value.asText(),DateTimeFormatter.BASIC_ISO_DATE).equals(date))
                throw new IllegalArgumentException("Share-float announcement date must match its probe date");
        }
        if(this == AUDIT && column.source().equals("end_date")) {
            if(!value.isTextual() || !value.asText().matches("[0-9]{8}")) throw new IllegalArgumentException("Invalid audit report period");
            if(LocalDate.parse(value.asText(),DateTimeFormatter.BASIC_ISO_DATE).isAfter(date))
                throw new IllegalArgumentException("Audit report period cannot follow its announcement date");
        }
    }
    Object sourceValue(SourceContract.Column column, Object value) {
        if(this == MONEYFLOW && column.type().equals("LONG") && value==null)
            throw new IllegalArgumentException("Frozen volume does not match registered null-to-zero transform");
        if(column.type().startsWith("TIMESTAMP") && value!=null) {
            if(this == MONTH && column.source().equals("month"))
                return YearMonth.parse(value.toString().substring(0,7)).format(SourceContract.BASIC_MONTH);
            LocalDate parsed=LocalDate.parse(value.toString());
            return parsed.format(DateTimeFormatter.BASIC_ISO_DATE);
        }
        return value;
    }
}
