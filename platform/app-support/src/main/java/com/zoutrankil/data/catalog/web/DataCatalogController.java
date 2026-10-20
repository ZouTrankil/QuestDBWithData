package com.zoutrankil.data.catalog.web;

import com.zoutrankil.data.catalog.application.DataCatalogService;
import com.zoutrankil.data.catalog.domain.CatalogTag;
import com.zoutrankil.data.catalog.domain.DataCatalog;
import com.zoutrankil.data.catalog.domain.DataObjectDescription;
import com.zoutrankil.data.domain.DatasetDefinition;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/** 对外提供目录查询与导出；数据库调用离开 WebFlux 事件线程执行。 */
@RestController
@RequestMapping(path = "/api/v1/data-catalog", produces = MediaType.APPLICATION_JSON_VALUE)
public final class DataCatalogController {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final DataCatalogService catalog;

    public DataCatalogController(DataCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public Mono<DataCatalog.Page> list(
            @RequestParam(name = "market", required = false) String market,
            @RequestParam(name = "sourceType", required = false) String sourceType,
            @RequestParam(name = "objectKind", required = false) String objectKind,
            @RequestParam(name = "tag", required = false) List<String> tags,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "includeMissing", defaultValue = "false") boolean includeMissing,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "100") int pageSize) {
        var filter = filter(market, sourceType, objectKind, tags, keyword, includeMissing);
        return call(() -> catalog.page(filter, page, pageSize));
    }

    @GetMapping("/tags")
    public Mono<DataCatalog.Tags> tags() {
        return call(catalog::tags);
    }

    @GetMapping("/objects/{objectName}")
    public Mono<DataCatalog.Detail> detail(@PathVariable("objectName") String name) {
        return call(() -> catalog.detail(name));
    }

    @GetMapping(path = "/export", produces = "text/csv")
    public Mono<ResponseEntity<byte[]>> export(
            @RequestParam(name = "format", defaultValue = "csv") String format,
            @RequestParam(name = "market", required = false) String market,
            @RequestParam(name = "sourceType", required = false) String sourceType,
            @RequestParam(name = "objectKind", required = false) String objectKind,
            @RequestParam(name = "tag", required = false) List<String> tags,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "includeMissing", defaultValue = "false") boolean includeMissing) {
        if (!"csv".equalsIgnoreCase(format))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 csv 格式");
        var filter = filter(market, sourceType, objectKind, tags, keyword, includeMissing);
        return call(
                () -> {
                    var result = catalog.list(filter);
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                            .header(
                                    "Content-Disposition",
                                    ContentDisposition.attachment()
                                            .filename(
                                                    "questdb-data-catalog-"
                                                            + FILE_TIME.format(result.observedAt())
                                                            + ".csv")
                                            .build()
                                            .toString())
                            .header("X-Data-Catalog-Observed-At", result.observedAt().toString())
                            .body(DataCatalogCsv.encode(result));
                });
    }

    private static DataCatalog.Filter filter(
            String market,
            String sourceType,
            String objectKind,
            List<String> tags,
            String keyword,
            boolean missing) {
        try {
            Set<CatalogTag> selected =
                    tags == null
                            ? Set.of()
                            : tags.stream()
                                    .map(tag -> parse(CatalogTag.class, tag))
                                    .collect(Collectors.toSet());
            return new DataCatalog.Filter(
                    parse(DataObjectDescription.Market.class, market),
                    parse(DataObjectDescription.SourceType.class, sourceType),
                    parse(DatasetDefinition.ObjectKind.class, objectKind),
                    selected,
                    keyword,
                    missing);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录筛选参数无效", error);
        }
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
    }

    private static <T> Mono<T> call(Callable<T> action) {
        return Mono.fromCallable(action)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(
                        DataCatalogService.ObjectNotFoundException.class,
                        error -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到数据对象"))
                .onErrorMap(
                        IllegalArgumentException.class,
                        error -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "目录请求参数无效"))
                .onErrorMap(
                        DataAccessException.class,
                        error ->
                                new ResponseStatusException(
                                        HttpStatus.SERVICE_UNAVAILABLE, "QuestDB 元数据暂不可用"));
    }
}
