package com.zoutrankil.batch;

import java.time.*;
import java.util.*;

/** Immutable versioned certificate; a process exit code is never a certificate. */
public record CompletionEvidence(int schemaVersion, String producer, String instanceId, String stage,
        String batchId, LocalDate logicalDate, LocalDate requestedStart, LocalDate requestedEnd,
        LocalDate actualStart, LocalDate actualEnd, String definitionVersion, String sourceVersion,
        String inputFingerprint, long readRows, long writtenRows, boolean uniqueKeys,
        boolean completeCoverage, boolean physicallyVisible, boolean qualityPassed,
        boolean sourceProbeComplete, boolean emptyAllowed, Instant availableAt,
        BusinessState state, String artifact, String error) {
    public CompletionEvidence {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported evidence schema");
        Objects.requireNonNull(state); Objects.requireNonNull(logicalDate);
        Objects.requireNonNull(requestedStart); Objects.requireNonNull(requestedEnd);
        for (String s : List.of(producer, instanceId, stage, definitionVersion, sourceVersion, inputFingerprint))
            if (s.isBlank()) throw new IllegalArgumentException("Missing evidence identity");
        if (readRows < 0 || writtenRows < 0 || requestedStart.isAfter(requestedEnd))
            throw new IllegalArgumentException("Invalid coverage/counts");
        if (state.ready()) {
            if (!uniqueKeys || !completeCoverage || !physicallyVisible || !qualityPassed
                    || !sourceProbeComplete || availableAt == null || artifact == null || artifact.isBlank())
                throw new IllegalArgumentException("Ready requires physical, source, quality and artifact evidence");
            if (!requestedStart.equals(actualStart) || !requestedEnd.equals(actualEnd))
                throw new IllegalArgumentException("Incomplete actual coverage");
            if (state == BusinessState.VERIFIED_EMPTY && (!emptyAllowed || writtenRows != 0 || readRows != 0))
                throw new IllegalArgumentException("Unproven empty window");
            if (state == BusinessState.VERIFIED && writtenRows == 0)
                throw new IllegalArgumentException("Nonempty certificate requires rows");
        }
    }
    public boolean matches(RunRequest request, String expectedStage) {
        return instanceId.equals(request.instanceId()) && logicalDate.equals(request.logicalDate())
                && requestedStart.equals(request.rangeStart()) && requestedEnd.equals(request.rangeEnd())
                && definitionVersion.equals(request.definitionVersion())
                && inputFingerprint.equals(request.inputFingerprint()) && stage.equals(expectedStage);
    }
}
