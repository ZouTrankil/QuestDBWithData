package com.zoutrankil.data.derived.domain;

import com.zoutrankil.data.domain.*;
import com.zoutrankil.data.domain.table.*;
import java.time.*;
import java.util.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
public final class NativeDailyProjection<R extends Record> {
        private final List<String> columns;
        private final RecordComponent[] components;
        private final Constructor<R> constructor;
        public NativeDailyProjection(Class<R> rowType,List<String> columns) {
            if(!Objects.requireNonNull(rowType).isRecord())throw new IllegalArgumentException("Typed record projection required");
            this.columns=List.copyOf(columns);components=rowType.getRecordComponents();
            if(components.length!=this.columns.size()||this.columns.isEmpty()||!this.columns.getFirst().equals("trade_date")
                    ||components[0].getType()!=Instant.class||!components[0].getName().equals("tradeDate")||new HashSet<>(this.columns).size()!=this.columns.size())
                throw new IllegalArgumentException("Exact date-first record projection required");
            for(int i=0;i<components.length;i++) {
                DatasetDefinition.identifier(this.columns.get(i));Class<?> type=components[i].getType();
                if(type!=Instant.class&&type!=Double.class&&type!=Boolean.class&&type!=String.class)
                    throw new IllegalArgumentException("Unsupported typed daily field: "+components[i].getName());
            }
            try {constructor=rowType.getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));}
            catch(ReflectiveOperationException failure){throw new IllegalArgumentException("Typed record constructor required",failure);}
        }
        public Instant key(R row) {
            Object value=values(row).get("trade_date");
            if(!(value instanceof Instant instant)||!instant.equals(instant.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)))
                throw new IllegalArgumentException("Exact UTC midnight business date required");
            return instant;
        }
        public byte[] canonicalBytes(R row) {
            try{return JobDefinitionJson.mapper().writeValueAsBytes(values(row));}
            catch(Exception failure){throw new IllegalArgumentException("Cannot encode typed daily row",failure);}
        }
        public int estimatedTransportBytes(R row,byte[] canonical){return Math.addExact(canonical.length*3,1024);}
        public boolean outside(R row,LocalDate from,LocalDate to) {
            var date=key(row).atOffset(ZoneOffset.UTC).toLocalDate();return date.isBefore(from)||date.isAfter(to);
        }
        public R row(Map<String,?> map) {
            Object[] args=new Object[columns.size()];for(int i=0;i<args.length;i++){args[i]=map.get(columns.get(i));if(args[i] instanceof Double d&&!Double.isFinite(d))args[i]=null;}
            if(!(args[0] instanceof Instant instant)||!instant.equals(instant.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)))
                throw new IllegalArgumentException("Exact UTC midnight business date required");
            try{return constructor.newInstance(args);}catch(ReflectiveOperationException failure){throw new IllegalArgumentException("Invalid typed daily row",failure);}
        }
        public Map<String,Object> values(R row) {
            var map=new LinkedHashMap<String,Object>();try {for(int i=0;i<components.length;i++) {
                Object value=components[i].getAccessor().invoke(row);if(value instanceof Double d&&!Double.isFinite(d))throw new IllegalArgumentException("Nonfinite typed daily value");map.put(columns.get(i),value);
            }return Collections.unmodifiableMap(map);}catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot map typed daily projection",failure);}
        }
        public String quotedColumns(){return String.join(",",columns.stream().map(c->"\""+c+"\"").toList());}
        public String digest(List<R> rows) {
            try {var sha=MessageDigest.getInstance("SHA-256");for(var row:rows){sha.update(canonicalBytes(row));sha.update((byte)'\n');}return HexFormat.of().formatHex(sha.digest());}
            catch(Exception failure){throw new IllegalStateException("Cannot hash typed daily snapshot",failure);}
        }
    }
