package com.zoutrankil.batch;

import com.zoutrankil.data.service.PageExecutor;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Native Java collection, normalization and immutable source retention; never calls Python. */
public final class SourceCollector {
    @FunctionalInterface public interface ContractFetcher {
        PageExecutor.Page fetch(com.zoutrankil.data.domain.PageContract providerContract,
                                Map<String,Object> params) throws Exception;
    }
    public record Request(String dataset,LocalDate logicalDate,Set<String> expectedCodes,String universeVersion,String tsCode,
                          LocalDate rangeStart,LocalDate rangeEnd) {
        public Request(String dataset,LocalDate logicalDate,Set<String> expectedCodes,String universeVersion,String tsCode) {
            this(dataset,logicalDate,expectedCodes,universeVersion,tsCode,logicalDate,logicalDate);
        }
        public Request {
            var contract=SourceContract.load(dataset); Objects.requireNonNull(logicalDate);
            Objects.requireNonNull(rangeStart); Objects.requireNonNull(rangeEnd);
            if(rangeStart.isAfter(rangeEnd)||!logicalDate.equals(rangeEnd)) throw new IllegalArgumentException("Logical date must equal range end");
            if(SourceContract.MONTHLY_AGGREGATES.contains(dataset)) {
                long months=java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(rangeStart),java.time.YearMonth.from(rangeEnd))+1;
                if(rangeStart.getDayOfMonth()!=1||rangeEnd.getDayOfMonth()!=1||months>Math.min(contract.maxRows(),2000))
                    throw new IllegalArgumentException("Monthly range must use month starts and stay within the source row budget");
            } else if(SourceContract.QUARTERLY_AGGREGATES.contains(dataset)) {
                long quarters=quartersBetween(rangeStart,rangeEnd)+1;
                if(!isQuarterEnd(rangeStart)||!isQuarterEnd(rangeEnd)||quarters>Math.min(contract.maxRows(),2000))
                    throw new IllegalArgumentException("Quarterly range must use quarter ends and stay within the source row budget");
            } else if(!rangeStart.equals(logicalDate)||!rangeEnd.equals(logicalDate)) throw new IllegalArgumentException("Only monthly or quarterly aggregates support ranges");
            expectedCodes=Collections.unmodifiableSet(new TreeSet<>(expectedCodes));
            if(dataset.equals("fina_mainbz") && (!Set.of(3,6,9,12).contains(logicalDate.getMonthValue())
                    || logicalDate.getDayOfMonth()!=logicalDate.lengthOfMonth()))
                throw new IllegalArgumentException("fina_mainbz logical date must be a quarter end");
            if(SourceContract.MONTHLY_AGGREGATES.contains(dataset) && logicalDate.getDayOfMonth()!=1)
                throw new IllegalArgumentException(dataset+" logical date must identify the first day of its observation month");
            if(SourceContract.QUARTERLY_AGGREGATES.contains(dataset)
                    && !(Set.of(3,6,9,12).contains(logicalDate.getMonthValue())&&logicalDate.getDayOfMonth()==logicalDate.lengthOfMonth()))
                throw new IllegalArgumentException(dataset+" logical date must identify a quarter end");
            if((!contract.isMarketAggregate() && expectedCodes.isEmpty()) || (contract.isMarketAggregate() && (!expectedCodes.isEmpty() || tsCode!=null)) || expectedCodes.size()>20000 || universeVersion==null || universeVersion.isBlank())
                throw new IllegalArgumentException("Versioned bounded expected entity coverage required");
            if(expectedCodes.stream().anyMatch(c -> !contract.validCode(c))) throw new IllegalArgumentException("Invalid universe code");
            if(tsCode!=null && (!expectedCodes.equals(Set.of(tsCode)))) throw new IllegalArgumentException("Filtered source scope must match expected universe");
            if(contract.dataset().equals("fina_mainbz") && expectedCodes.size()!=1)
                throw new IllegalArgumentException("fina_mainbz currently requires one explicit security scope");
            if(contract.dataset().equals("dividend") && expectedCodes.size()!=1)
                throw new IllegalArgumentException("dividend currently requires one explicit security scope per announcement date");
            if(contract.dataset().equals("fina_audit") && expectedCodes.size()!=1)
                throw new IllegalArgumentException("fina_audit currently requires one explicit security scope per announcement date");
            if(Set.of("fut_daily","fut_settle","fut_mapping","ft_limit","fut_holding","fut_basic").contains(contract.dataset()) && expectedCodes.size()!=1)
                throw new IllegalArgumentException(contract.dataset()+" currently requires one explicit futures contract/product per trade date");
        }
    }
    public record Frozen(int schemaVersion,String dataset,String definitionVersion,LocalDate logicalDate,LocalDate rangeStart,LocalDate rangeEnd,
                         Set<String> expectedCodes,String universeVersion,String sourceDocumentation,
                         List<Map<String,Object>> rows,int sourcePages,boolean completeCoverage) {
        public Frozen {
            if((schemaVersion!=1&&schemaVersion!=2) || sourcePages<1) throw new IllegalArgumentException("Unknown frozen-source schema or missing probe evidence");
            // Schema v1 archives represented exactly one logical date and remain valid recovery inputs.
            if(rangeStart==null) rangeStart=logicalDate;
            if(rangeEnd==null) rangeEnd=logicalDate;
            new Request(dataset,logicalDate,expectedCodes,universeVersion,null,rangeStart,rangeEnd);
            expectedCodes=Collections.unmodifiableSet(new TreeSet<>(expectedCodes));
            rows=rows.stream().map(r -> Collections.unmodifiableMap(new LinkedHashMap<>(r))).toList();
        }
    }
    public record Collected(String fingerprint,String artifact,int rows,int pages,boolean completeCoverage,
                            String scopeIdentity,BusinessState state) {}
    private final Path root;
    public SourceCollector(Path archiveRoot) { root=archiveRoot.toAbsolutePath().normalize().resolve("sources"); }
    public Collected collect(Request request,PageExecutor.Fetcher source) throws Exception {
        return collect(request,(providerContract,params) -> source.fetch(params));
    }
    public Collected collect(Request request,ContractFetcher source) throws Exception {
        var contract=SourceContract.load(request.dataset()); var normalized=new ArrayList<Map<String,Object>>();
        var params=new LinkedHashMap<String,Object>();
        if(!Set.of("fut_basic","etf_basic","ths_index").contains(contract.dataset())) params.put(contract.dateParameter(),SourceContract.MONTHLY_AGGREGATES.contains(contract.dataset())
                ?request.logicalDate().format(DateTimeFormatter.ofPattern("yyyyMM")):request.logicalDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        if(contract.dataset().equals("stk_st_daily")) params.put("start_date","20100101");
        if(contract.dataset().equals("exchange_calendar")) params.put("end_date",request.logicalDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        if(Set.of("shibor","shibor_lpr","hibor").contains(contract.dataset())) params.put("end_date",request.logicalDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        if(SourceContract.MONTHLY_AGGREGATES.contains(contract.dataset())) {
            params.remove("m");
            if(request.rangeStart().equals(request.rangeEnd())) params.put("m",request.logicalDate().format(DateTimeFormatter.ofPattern("yyyyMM")));
            else { params.put("start_m",request.rangeStart().format(DateTimeFormatter.ofPattern("yyyyMM"))); params.put("end_m",request.rangeEnd().format(DateTimeFormatter.ofPattern("yyyyMM"))); }
        }
        if(SourceContract.QUARTERLY_AGGREGATES.contains(contract.dataset())) {
            if(request.rangeStart().equals(request.rangeEnd())) params.put("q",quarter(request.logicalDate()));
            else { params.remove("q");params.put("start_q",quarter(request.rangeStart())); params.put("end_q",quarter(request.rangeEnd())); }
        }
        if(contract.dataset().equals("cn_bond_yield_curve") || contract.dataset().equals("moneyflow_hsgt")) params.put("end_date",request.logicalDate().format(DateTimeFormatter.BASIC_ISO_DATE));
        if(contract.dataset().equals("stk_suspend")) params.put("suspend_type","S");
        if(contract.dataset().equals("fut_holding")) params.put("exchange","CFFEX");
        if(contract.dataset().equals("fut_basic")) params.put("fut_type","1");
        if(contract.dataset().equals("etf_basic")) params.put("market","E");
        final int[] bytes={0},rawBytes={0},rawRows={0};var rawArtifacts=new ArrayList<String>();
        final int[] completedPages={0},completedRows={0};
        var stHistory=new ArrayList<Map<String,com.fasterxml.jackson.databind.JsonNode>>();
        PageExecutor.Fetcher retainedSource=pageParams -> {
            String providerCode=(String)pageParams.get("ts_code");
            var page=source.fetch(contract.providerContract(providerCode),pageParams);
            if(page==null) return null;
            if(contract.dataset().equals("fina_mainbz")) {
                String requestedType=Objects.toString(pageParams.get("type"),"");
                if(!Set.of("P","D").contains(requestedType) || page.rows().stream().anyMatch(row -> {
                    var category=row.get("bz_code");return category==null||!category.isTextual()||!requestedType.equals(category.asText().trim());
                })) throw new IllegalArgumentException("fina_mainbz response category does not match request");
            }
            if(page.rows().size()>contract.pageSize()) throw new IllegalArgumentException("Raw page row bound exceeded");
            rawRows[0]=Math.addExact(rawRows[0],page.rows().size());
            byte[] raw=Json.MAPPER.writeValueAsBytes(page);
            rawBytes[0]=Math.addExact(rawBytes[0],raw.length);
            if(rawBytes[0]>contract.maxBytes()) throw new IllegalArgumentException("Raw source byte budget exceeded");
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            Path artifact=root.resolve("raw").resolve(digest+".json");retain(artifact,raw);rawArtifacts.add(artifact.toString());
            List<Map<String,com.fasterxml.jackson.databind.JsonNode>> prepared;
            if(SourceContract.QUARTERLY_AGGREGATES.contains(contract.dataset())) {
                var perQuarter=new ArrayList<Map<String,com.fasterxml.jackson.databind.JsonNode>>();
                for(var row:page.rows()) perQuarter.addAll(contract.prepareRows(List.of(row),observationDate(contract,row,request.logicalDate()),request.expectedCodes(),providerCode));
                prepared=List.copyOf(perQuarter);
            } else prepared=contract.prepareRows(page.rows(),request.logicalDate(),request.expectedCodes(),providerCode);
            return new PageExecutor.Page(prepared,
                    page.nextCursor(),page.explicitEnd(),page.sourceVersion());
        };
        var queryCodes=Set.of("index_daily_market","index_daily_basic","exchange_calendar","fina_mainbz","dividend","fut_daily","fut_settle","fut_mapping","ft_limit","fut_holding","fut_basic","etf_basic").contains(contract.dataset())
                ?new ArrayList<>(request.expectedCodes()):new ArrayList<String>();
        if(queryCodes.isEmpty()) queryCodes.add(request.tsCode());
        for(String code:queryCodes) {
          var categories=contract.dataset().equals("fina_mainbz")?List.of("P","D"):java.util.Arrays.asList((String)null);
          for(String category:categories) {
            var queryParams=new LinkedHashMap<>(params);
            if(code!=null) queryParams.put(contract.dataset().equals("exchange_calendar")||contract.dataset().equals("fut_basic")?"exchange":contract.dataset().equals("etf_basic")?"market":contract.dataset().equals("fut_holding")?"symbol":"ts_code",code);
            if(category!=null) queryParams.put("type",category);
            var completed=new PageExecutor().execute(contract.pageContract(),queryParams,retainedSource,(page,receipt) -> {
                if(contract.dataset().equals("stk_st_daily")) { stHistory.addAll(page.rows()); return; }
                for(var row:page.rows()) {
                    LocalDate observation=observationDate(contract,row,request.logicalDate());
                    if(observation.isBefore(request.rangeStart())||observation.isAfter(request.rangeEnd()))
                        throw new IllegalArgumentException("Source observation is outside requested range");
                    if(contract.ignoredFooter(row,observation)) continue;
                    var value=contract.normalize(row,observation,request.expectedCodes());
                    bytes[0]=Math.addExact(bytes[0],Json.write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                    if(bytes[0]>contract.maxBytes()) throw new IllegalArgumentException("Frozen source byte budget exceeded");
                    normalized.add(value);
                }
            },row -> {
                if(contract.dataset().equals("stk_st_daily")) validateStEvent(row);
                else { LocalDate observation=observationDate(contract,row,request.logicalDate());
                    if(observation.isBefore(request.rangeStart())||observation.isAfter(request.rangeEnd())) throw new IllegalArgumentException("Source observation is outside requested range");
                    if(!contract.ignoredFooter(row,observation)) contract.normalize(row,observation,request.expectedCodes()); }
            },() -> Thread.currentThread().isInterrupted());
            completedPages[0]=Math.addExact(completedPages[0],completed.pages());
            completedRows[0]=Math.addExact(completedRows[0],completed.rows());
          }
        }
        if(contract.dataset().equals("stk_st_daily")) {
            var active=new TreeSet<String>();
            for(var event:stHistory) {
                String name=event.get("name").asText();
                if(!name.toUpperCase(Locale.ROOT).contains("ST")) continue;
                LocalDate start=LocalDate.parse(event.get("start_date").asText(),DateTimeFormatter.BASIC_ISO_DATE);
                var endNode=event.get("end_date");
                LocalDate end=endNode==null||endNode.isNull()||endNode.asText().isBlank()?request.logicalDate():LocalDate.parse(endNode.asText(),DateTimeFormatter.BASIC_ISO_DATE);
                if(!start.isAfter(request.logicalDate())&&!end.isBefore(request.logicalDate())) active.add(event.get("ts_code").asText().trim());
            }
            for(String code:active) {
                var row=new LinkedHashMap<String,com.fasterxml.jackson.databind.JsonNode>();
                row.put("ts_code",Json.MAPPER.valueToTree(code)); row.put("trade_date",Json.MAPPER.valueToTree(request.logicalDate().format(DateTimeFormatter.BASIC_ISO_DATE)));
                row.put("is_st",Json.MAPPER.valueToTree(1)); normalized.add(contract.normalize(row,request.logicalDate(),request.expectedCodes()));
            }
        }
        if(contract.dataset().equals("fina_mainbz")) {
            var keys=new HashMap<String,String>();
            for(var row:normalized) {
                String key=contract.key(row),category=Objects.toString(row.get("bz_code"),"");
                String prior=keys.putIfAbsent(key,category);
                if(prior!=null) throw new IllegalArgumentException("P/D categories collide under the registered fina_mainbz business key");
            }
        }
        normalized.sort(Comparator.comparing(contract::key));
        boolean coverage=contract.covers(normalized,request.expectedCodes());
        if(SourceContract.MONTHLY_AGGREGATES.contains(contract.dataset())) coverage &= coversMonths(normalized,request.rangeStart(),request.rangeEnd());
        if(SourceContract.QUARTERLY_AGGREGATES.contains(contract.dataset())) coverage &= coversQuarters(normalized,request.rangeStart(),request.rangeEnd());
        var frozen=new Frozen(2,contract.dataset(),contract.version(),request.logicalDate(),request.rangeStart(),request.rangeEnd(),request.expectedCodes(),request.universeVersion(),
                contract.sourceDocumentation(),normalized,completedPages[0],coverage);
        // Observation time is in the probe receipt, not the content identity. Repeating equal input is stable.
        byte[] content=Json.MAPPER.writeValueAsBytes(frozen);
        if(content.length>contract.maxBytes()) throw new IllegalArgumentException("Frozen manifest byte budget exceeded");
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        Path target=root.resolve(fingerprint+".json");retain(target,content);
        Files.writeString(root.resolve(fingerprint+".probe-"+UUID.randomUUID()+".json"),Json.write(Map.of("observedAt",Instant.now(),"rows",normalized.size(),"sourceRows",rawRows[0],"filteredFooterRows",completedRows[0]-normalized.size(),"collapsedOrFilteredEventRows",rawRows[0]-completedRows[0],"rawArtifacts",rawArtifacts,"pages",completedPages[0],"sourceProbeComplete",true)),StandardOpenOption.CREATE_NEW);
        return new Collected(fingerprint,target.toString(),normalized.size(),completedPages[0],coverage,scopeIdentity(frozen),
                normalized.isEmpty()&&!contract.emptyAllowed()?BusinessState.WAITING_SOURCE:!coverage?BusinessState.PARTIAL:
                    SourceQuality.failures(contract.dataset(),normalized).isEmpty()?BusinessState.VERIFYING:BusinessState.BLOCKED);
    }
    public static String scopeIdentity(Frozen frozen) {
        return RunRequest.hash(frozen.dataset(),frozen.universeVersion(),String.join("\n",frozen.expectedCodes()));
    }
    private static LocalDate observationDate(SourceContract contract,Map<String,com.fasterxml.jackson.databind.JsonNode> row,LocalDate fallback) {
        if(SourceContract.MONTHLY_AGGREGATES.contains(contract.dataset())) {
            var month=row.get("month"); if(month==null||!month.isTextual()) return fallback;
            return java.time.YearMonth.parse(month.asText(),DateTimeFormatter.ofPattern("yyyyMM")).atDay(1);
        }
        if(SourceContract.QUARTERLY_AGGREGATES.contains(contract.dataset())) {
            var quarter=row.get("quarter"); if(quarter==null||!quarter.isTextual()||!quarter.asText().matches("[0-9]{4}Q[1-4]")) return fallback;
            int year=Integer.parseInt(quarter.asText().substring(0,4)),q=Character.digit(quarter.asText().charAt(5),10);
            return YearMonth.of(year,q*3).atEndOfMonth();
        }
        return fallback;
    }
    static boolean coversMonths(List<Map<String,Object>> rows,LocalDate start,LocalDate end) {
        if(start.equals(end)&&rows.isEmpty()) return true;
        var months=new TreeSet<LocalDate>(); for(var row:rows) { Object value=row.get("month"); if(!(value instanceof String s)) return false; months.add(LocalDate.parse(s)); }
        long expected=java.time.temporal.ChronoUnit.MONTHS.between(java.time.YearMonth.from(start),java.time.YearMonth.from(end))+1;
        if(months.size()!=expected) return false;
        var cursor=java.time.YearMonth.from(start); for(LocalDate month:months) { if(!month.equals(cursor.atDay(1))) return false; cursor=cursor.plusMonths(1); }
        return true;
    }
    static boolean coversQuarters(List<Map<String,Object>> rows,LocalDate start,LocalDate end) {
        if(start.equals(end)&&rows.isEmpty()) return true;
        var quarters=new TreeSet<LocalDate>();for(var row:rows) { Object value=row.get("report_date");if(!(value instanceof String s))return false;quarters.add(LocalDate.parse(s)); }
        long expected=quartersBetween(start,end)+1;if(quarters.size()!=expected)return false;
        var cursor=YearMonth.from(start);for(LocalDate date:quarters) { if(!date.equals(cursor.atEndOfMonth()))return false;cursor=cursor.plusMonths(3); }
        return true;
    }
    static String quarter(LocalDate date) { return date.getYear()+"Q"+((date.getMonthValue()-1)/3+1); }
    static boolean isQuarterEnd(LocalDate date) { return Set.of(3,6,9,12).contains(date.getMonthValue())&&date.getDayOfMonth()==date.lengthOfMonth(); }
    static long quartersBetween(LocalDate start,LocalDate end) { return YearMonth.from(start).until(YearMonth.from(end),java.time.temporal.ChronoUnit.MONTHS)/3; }
    private static void validateStEvent(Map<String,com.fasterxml.jackson.databind.JsonNode> row) {
        for(String field:List.of("ts_code","name","start_date","end_date","ann_date","change_reason"))
            if(!row.containsKey(field)) throw new IllegalArgumentException("ST history schema drift: missing "+field);
        String code=row.get("ts_code").asText().trim();
        if(!code.matches("[0-9]{6}\\.(SH|SZ|BJ)")) throw new IllegalArgumentException("Invalid ST history code");
        if(!row.get("name").isTextual()||!row.get("start_date").isTextual()||!row.get("ann_date").isTextual()) throw new IllegalArgumentException("Invalid ST history fields");
        LocalDate.parse(row.get("start_date").asText(),DateTimeFormatter.BASIC_ISO_DATE);
        LocalDate.parse(row.get("ann_date").asText(),DateTimeFormatter.BASIC_ISO_DATE);
        var end=row.get("end_date"); if(end!=null&&!end.isNull()&&!end.asText().isBlank()) LocalDate.parse(end.asText(),DateTimeFormatter.BASIC_ISO_DATE);
    }
    private static void retain(Path target,byte[] content) throws java.io.IOException {
        Files.createDirectories(target.getParent());
        if(Files.exists(target)) {
            if(!Arrays.equals(Files.readAllBytes(target),content)) throw new IllegalArgumentException("Retained content changed");
            return;
        }
        Path temp=Files.createTempFile(target.getParent(),"collect-",".partial");
        try {
            try(var file=FileChannel.open(temp,StandardOpenOption.WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(content);while(buffer.hasRemaining()) file.write(buffer);file.force(true);
            }
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
    public boolean sameScope(RunRequest previous,RunRequest next) {
        try {
            Frozen left=read(previous.inputFingerprint()),right=read(next.inputFingerprint());
            return left.dataset().equals(right.dataset()) && left.logicalDate().equals(right.logicalDate()) && left.rangeStart().equals(right.rangeStart()) && left.rangeEnd().equals(right.rangeEnd())
                    && left.definitionVersion().equals(right.definitionVersion()) && left.expectedCodes().equals(right.expectedCodes())
                    && left.universeVersion().equals(right.universeVersion());
        } catch(Exception invalid) { return false; }
    }
    public Frozen read(String fingerprint) throws Exception {
        if(fingerprint==null || !fingerprint.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Source SHA-256 required");
        Path path=root.resolve(fingerprint+".json");
        if(Files.size(path)>64L*1024*1024) throw new IllegalArgumentException("Frozen source exceeds memory budget");
        byte[] content=Files.readAllBytes(path);
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).equals(fingerprint))
            throw new IllegalArgumentException("Frozen source content changed");
        return Json.MAPPER.readValue(content,Frozen.class);
    }
    public Path path(String fingerprint) {
        if(fingerprint==null || !fingerprint.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Source SHA-256 required");
        return root.resolve(fingerprint+".json");
    }
}
