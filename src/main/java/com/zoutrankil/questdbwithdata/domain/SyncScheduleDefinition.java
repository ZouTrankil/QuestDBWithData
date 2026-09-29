package com.zoutrankil.questdbwithdata.domain;

import java.time.*;
import java.time.zone.ZoneRules;
import java.util.*;
import java.util.function.Predicate;

/** An explicit, finite local-time schedule. Registration never starts a timer or a data run. */
public record SyncScheduleDefinition(String scheduleId, Target target, String targetId, int targetVersion,
                                     boolean enabled, ZoneId zone, Kind kind, LocalTime time,
                                     Set<DayOfWeek> days, DayRule dayRule, Misfire misfire,
                                     Duration maxLateness, Map<String,String> parameters, int intervalMinutes) {
    public enum Target { JOB, GROUP }
    public enum Kind { DAILY, WEEKLY, MONTH_END, INTERVAL }
    public enum DayRule { CALENDAR, WEEKDAY, EXCHANGE_SESSION }
    public enum Misfire { SKIP, RUN_ONCE }
    public SyncScheduleDefinition(String scheduleId, Target target, String targetId, int targetVersion,
                                  boolean enabled, ZoneId zone, Kind kind, LocalTime time,
                                  Set<DayOfWeek> days, DayRule dayRule, Misfire misfire,
                                  Duration maxLateness) {
        this(scheduleId,target,targetId,targetVersion,enabled,zone,kind,time,days,dayRule,misfire,
                maxLateness,Map.of(),0);
    }
    public SyncScheduleDefinition(String scheduleId, Target target, String targetId, int targetVersion,
                                  boolean enabled, ZoneId zone, Kind kind, LocalTime time,
                                  Set<DayOfWeek> days, DayRule dayRule, Misfire misfire,
                                  Duration maxLateness, Map<String,String> parameters) {
        this(scheduleId,target,targetId,targetVersion,enabled,zone,kind,time,days,dayRule,misfire,
                maxLateness,parameters,0);
    }
    public SyncScheduleDefinition {
        SyncJobDefinition.name(scheduleId);
        SyncJobDefinition.name(targetId);
        Objects.requireNonNull(target); Objects.requireNonNull(zone); Objects.requireNonNull(kind);
        Objects.requireNonNull(time); Objects.requireNonNull(dayRule); Objects.requireNonNull(misfire);
        if (time.getSecond()!=0 || time.getNano()!=0)
            throw new IllegalArgumentException("Minute-aligned local schedule time required");
        if (targetVersion < 1) throw new IllegalArgumentException("Positive target version required");
        days = Set.copyOf(days);
        if (kind == Kind.WEEKLY && days.isEmpty()) throw new IllegalArgumentException("Weekly days required");
        if (kind == Kind.INTERVAL && (intervalMinutes < 1 || intervalMinutes > 1440)
                || kind != Kind.INTERVAL && intervalMinutes != 0)
            throw new IllegalArgumentException("Interval minutes must be 1..1440 only for interval schedules");
        if (maxLateness == null || maxLateness.isNegative() || maxLateness.compareTo(Duration.ofDays(7)) > 0)
            throw new IllegalArgumentException("Bounded nonnegative misfire window required");
        parameters = Map.copyOf(parameters);
        if (parameters.size() > 16 || parameters.entrySet().stream().anyMatch(e -> e.getKey().length() > 64
                || e.getValue().length() > 4096))
            throw new IllegalArgumentException("Bounded schedule parameters required");
    }
    public record Slot(LocalDate localDate, Instant dueAt) {}
    public SyncScheduleDefinition withEnabled(boolean value) {
        return new SyncScheduleDefinition(scheduleId,target,targetId,targetVersion,value,zone,kind,time,
                days,dayRule,misfire,maxLateness,parameters,intervalMinutes);
    }
    /** Pure calendar calculation. Exchange sessions must come from a supplied authoritative calendar. */
    public Optional<Slot> nextAfter(Instant exclusive, Predicate<LocalDate> exchangeSession) {
        Objects.requireNonNull(exclusive); Objects.requireNonNull(exchangeSession);
        LocalDate first = exclusive.atZone(zone).toLocalDate();
        for (int offset = 0; offset <= 370; offset++) {
            LocalDate date = first.plusDays(offset);
            if (kind == Kind.WEEKLY && !days.contains(date.getDayOfWeek())
                    || !applicable(date,exchangeSession)) continue;
            if (kind == Kind.MONTH_END) {
                boolean laterEligible=false;
                for (LocalDate later=date.plusDays(1);later.getMonth()==date.getMonth();later=later.plusDays(1))
                    if (applicable(later,exchangeSession)) { laterEligible=true; break; }
                if (laterEligible) continue;
            }
            int startMinute=time.toSecondOfDay()/60;
            int stopMinute=kind == Kind.INTERVAL ? 1440 : startMinute+1;
            int step=kind == Kind.INTERVAL ? intervalMinutes : 1;
            for (int minute=startMinute;minute<stopMinute;minute+=step) {
                LocalDateTime wall = LocalDateTime.of(date,LocalTime.of(minute/60,minute%60));
                ZoneRules rules = zone.getRules();
                var offsets = rules.getValidOffsets(wall);
                // A missing DST wall time is skipped; an overlapping time uses the earlier occurrence.
                if (offsets.isEmpty()) continue;
                Instant due = wall.toInstant(offsets.getFirst());
                if (due.isAfter(exclusive)) return Optional.of(new Slot(date, due));
            }
        }
        return Optional.empty();
    }
    private boolean applicable(LocalDate date, Predicate<LocalDate> exchangeSession) {
        return switch (dayRule) {
            case CALENDAR -> true;
            case WEEKDAY -> date.getDayOfWeek().getValue() <= 5;
            case EXCHANGE_SESSION -> exchangeSession.test(date);
        };
    }
}
