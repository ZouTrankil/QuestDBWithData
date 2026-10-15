package com.zoutrankil.data.factor.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** One pool/date/factor availability mask, shared by all daily metrics.
 * The Python-compatible sample hash identifies members and recipe, not source values;
 * incremental reuse also requires an independent source revision fingerprint.
 */
public final class FrozenFactorSample {
    private FrozenFactorSample() {}
    public record Identity(LocalDate signalDate, String universeId, String universeVersion,
            LocalDate membershipAsOf, String membershipHash, String factorId,
            String definitionVersion, String labelDefinition, String evaluationProfile) {
        public Identity {
            Objects.requireNonNull(signalDate); Objects.requireNonNull(membershipAsOf);
            for (String value : List.of(universeId,universeVersion,membershipHash,factorId,
                    definitionVersion,labelDefinition,evaluationProfile))
                if (value.isBlank() || value.contains("|")) throw new IllegalArgumentException("Invalid sample identity");
            if (!membershipHash.matches("[0-9a-f]{64}") || membershipAsOf.isAfter(signalDate))
                throw new IllegalArgumentException("Invalid PIT membership identity");
        }
    }
    public record Observation(LocalDate date, String symbol, String factorId, String version, double value) {}
    public record Sample(Identity identity, String sampleHash, List<DailyFactorMetrics.Row> members,
            Map<String,Integer> availabilityReasons, DailyFactorMetrics.Result metrics) {
        public Sample { members=List.copyOf(members); availabilityReasons=Map.copyOf(availabilityReasons); }
    }
    public static Sample freeze(Identity identity, List<String> includedMembers,
            List<Observation> observations, List<DailyFactorLabels.Label> labels) {
        Objects.requireNonNull(identity);
        var factors = new HashMap<String,Double>();
        for (var row : observations)
            if (identity.signalDate().equals(row.date()) && identity.factorId().equals(row.factorId())
                    && identity.definitionVersion().equals(row.version())) factors.put(row.symbol(),row.value());
        var returns = new HashMap<String,Double>();
        for (var row : labels) if (identity.signalDate().equals(row.signalDate()))
            returns.put(row.symbol(),row.forwardReturn());
        var members = new ArrayList<DailyFactorMetrics.Row>();
        var seen = new HashSet<String>(); var reasons = new TreeMap<String,Integer>();
        for (String symbol : includedMembers) {
            if (symbol == null || symbol.isBlank() || symbol.contains(",") || !seen.add(symbol))
                throw new IllegalArgumentException("Unique frozen pool members required");
            double factor=factors.getOrDefault(symbol,Double.NaN),label=returns.getOrDefault(symbol,Double.NaN);
            if (!Double.isFinite(factor)) reasons.merge("factor_value_unavailable",1,Integer::sum);
            if (!Double.isFinite(label)) reasons.merge("fwd1_label_unavailable",1,Integer::sum);
            members.add(new DailyFactorMetrics.Row(symbol,factor,label));
        }
        var metrics=DailyFactorMetrics.evaluate(members);
        String payload=String.join("|",identity.membershipHash(),identity.factorId(),identity.definitionVersion(),
                identity.labelDefinition(),identity.evaluationProfile(),String.join(",",metrics.sampleSymbols()));
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
            return new Sample(identity,hash,members,reasons,metrics);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
