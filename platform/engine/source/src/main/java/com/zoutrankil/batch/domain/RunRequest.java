package com.zoutrankil.batch.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

public record RunRequest(String requestId, String job, LocalDate logicalDate, LocalDate rangeStart,
                         LocalDate rangeEnd, String definitionVersion, String revision,
                         String supersedes, String revisionReason, String inputFingerprint,
                         String calendarVersion, String zone, Instant scheduledAt, Instant triggeredAt,
                         String scopeIdentity) {
    public RunRequest(String requestId,String job,LocalDate logicalDate,LocalDate rangeStart,LocalDate rangeEnd,
                      String definitionVersion,String revision,String supersedes,String revisionReason,String inputFingerprint,
                      String calendarVersion,String zone,Instant scheduledAt,Instant triggeredAt) {
        this(requestId,job,logicalDate,rangeStart,rangeEnd,definitionVersion,revision,supersedes,revisionReason,inputFingerprint,
                calendarVersion,zone,scheduledAt,triggeredAt,"");
    }
    public RunRequest {
        if(scopeIdentity==null) scopeIdentity="";
        for (String v : List.of(requestId, job, definitionVersion, revision, inputFingerprint, calendarVersion, zone))
            if (v.isBlank() || v.length() > 256) throw new IllegalArgumentException("Nonblank bounded identities required");
        if(!scopeIdentity.isBlank()&&!scopeIdentity.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Scope identity must be SHA-256");
        Objects.requireNonNull(logicalDate); Objects.requireNonNull(rangeStart); Objects.requireNonNull(rangeEnd);
        Objects.requireNonNull(scheduledAt); Objects.requireNonNull(triggeredAt); ZoneId.of(zone);
        if (rangeStart.isAfter(rangeEnd) || logicalDate.isBefore(rangeStart) || logicalDate.isAfter(rangeEnd))
            throw new IllegalArgumentException("Logical date must be within requested range");
        if (!com.zoutrankil.batch.source.SourceStrategyRegistry.registeredJobs().contains(job))
            throw new IllegalArgumentException("Unregistered job: " + job);
        if (!"0".equals(revision) && (supersedes == null || supersedes.isBlank()
                || revisionReason == null || revisionReason.isBlank()))
            throw new IllegalArgumentException("Explicit revision requires predecessor and reason");
        if ("0".equals(revision) && (supersedes != null || revisionReason != null))
            throw new IllegalArgumentException("Initial revision cannot supersede another instance");
    }
    /** Trigger clocks, request UUIDs and attempts do not create new business instances. */
    public String instanceId() {
        var identity=List.of(job,logicalDate.toString(),rangeStart.toString(),rangeEnd.toString(),definitionVersion,revision);
        if(scopeIdentity.isBlank()) return hash(identity.toArray(String[]::new));
        var scoped=new ArrayList<>(identity);scoped.add(scopeIdentity);
        return hash(scoped.toArray(String[]::new));
    }
    public RunRequest withScopeIdentity(String scope) {
        return new RunRequest(requestId,job,logicalDate,rangeStart,rangeEnd,definitionVersion,revision,supersedes,revisionReason,
                inputFingerprint,calendarVersion,zone,scheduledAt,triggeredAt,scope);
    }
    /** Combines the registered canonical input with the current trigger's audit identity and clocks. */
    public RunRequest withTrigger(RunRequest trigger) {
        if(!instanceId().equals(trigger.instanceId())) throw new IllegalArgumentException("Trigger belongs to another business instance");
        return new RunRequest(trigger.requestId,job,logicalDate,rangeStart,rangeEnd,definitionVersion,revision,supersedes,
                revisionReason,inputFingerprint,calendarVersion,zone,trigger.scheduledAt,trigger.triggeredAt,scopeIdentity);
    }
    public String inputIdentity() { return hash(inputFingerprint, calendarVersion, zone); }
    public static String hash(String... parts) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = Objects.requireNonNull(part).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
