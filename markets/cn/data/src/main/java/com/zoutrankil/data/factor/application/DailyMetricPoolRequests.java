package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.engine.DailyFactorLabels;
import com.zoutrankil.data.factor.engine.FrozenFactorSample;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Preserve the declared pool/factor matrix when turning frozen pool evidence into daily evaluations. */
public final class DailyMetricPoolRequests {
    private DailyMetricPoolRequests() {}

    public record Plan(
            List<DailyFactorEvaluation.Request> availableRequests,
            Set<DailyMetricPublication.Key> expected,
            Map<String, String> unavailablePools,
            String poolEvidenceSha256) {
        public Plan {
            availableRequests = List.copyOf(availableRequests);
            expected = Set.copyOf(expected);
            unavailablePools = Map.copyOf(unavailablePools);
        }

        public boolean allPoolsAvailable() {
            return unavailablePools.isEmpty();
        }
    }

    public static Plan read(
            Path evidence, LocalDate date, String label, FactorDefinitionCatalog catalog)
            throws Exception {
        if (!Set.of(DailyFactorLabels.LEGACY, DailyFactorLabels.SUSPENSION_LAST).contains(label))
            throw new IllegalArgumentException("Canonical daily label required");
        var snapshot = FactorPoolEvidence.read(evidence);
        var json = JobDefinitionJson.mapper();
        var root = json.readTree(snapshot.bytes());
        var identity = root.path("inputIdentity");
        var factors = new TreeSet<String>();
        var pools = new TreeSet<String>();
        for (var node : identity.path("factors")) {
            String factor = node.asText();
            if (!catalog.require(factor).assetType().equals("stock"))
                throw new IllegalArgumentException("Stock pool evaluation required");
            factors.add(factor);
        }
        for (var node : identity.path("pools")) pools.add(node.asText());
        for (var section : root.path("sections")) {
            String factor = section.path("definition").path("factorId").asText();
            if (!section.path("definition").equals(json.valueToTree(catalog.require(factor))))
                throw new IllegalStateException("Frozen pool factor definition changed");
        }
        var expected = new HashSet<DailyMetricPublication.Key>();
        for (var pool : pools)
            for (var factor : factors) expected.add(new DailyMetricPublication.Key(pool, factor));
        var requests = new ArrayList<DailyFactorEvaluation.Request>();
        var unavailable = new TreeMap<String, String>();
        var seen = new HashSet<String>();
        for (var coverage : root.path("coverage"))
            if (date.toString().equals(coverage.path("date").asText())) {
                String pool = coverage.path("pool").asText();
                if (!pools.contains(pool) || !seen.add(pool))
                    throw new IllegalArgumentException("Unexpected pool coverage key");
                if (!coverage.path("available").asBoolean()) {
                    unavailable.put(pool, coverage.path("reason").asText("unavailable"));
                    continue;
                }
                var members = new ArrayList<String>();
                for (var member : coverage.path("members"))
                    if (member.path("reasons").isEmpty())
                        members.add(member.path("symbol").asText());
                members.sort(String::compareTo);
                for (var factor : factors)
                    requests.add(
                            new DailyFactorEvaluation.Request(
                                    new FrozenFactorSample.Identity(
                                            date,
                                            pool,
                                            coverage.path("version").asText(),
                                            LocalDate.parse(
                                                    coverage.path("membershipAsOf").asText()),
                                            coverage.path("membershipHash").asText(),
                                            factor,
                                            catalog.require(factor).version(),
                                            label,
                                            "base_ic"),
                                    members));
            }
        if (!seen.equals(pools))
            throw new IllegalArgumentException(
                    "Requested date has missing pool coverage declarations");
        return new Plan(requests, expected, unavailable, snapshot.sha256());
    }
}
