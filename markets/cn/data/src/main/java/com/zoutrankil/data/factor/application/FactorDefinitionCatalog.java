package com.zoutrankil.data.factor.application;

import com.zoutrankil.data.domain.JobDefinitionJson;
import com.zoutrankil.data.factor.domain.FactorDefinition;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Contract inventory, not a claim that every listed formula has a Java producer. */
public final class FactorDefinitionCatalog {
    private final Map<String, FactorDefinition> definitions;
    private final String sourceConfigSha256;

    private FactorDefinitionCatalog(Map<String, FactorDefinition> definitions, String sha) {
        this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        this.sourceConfigSha256 = sha;
    }

    public static FactorDefinitionCatalog load() throws IOException {
        try (var input =
                FactorDefinitionCatalog.class.getResourceAsStream("/factor/definitions-v2.json")) {
            if (input == null) throw new IOException("Factor definitions resource is missing");
            var mapper = JobDefinitionJson.mapper();
            var root = mapper.readTree(input);
            String sha = root.path("sourceConfigSha256").asText();
            if (!sha.matches("[0-9a-f]{64}") || !root.path("definitions").isArray())
                throw new IOException("Factor definition provenance is invalid");
            var definitions = new LinkedHashMap<String, FactorDefinition>();
            for (var item : root.path("definitions")) {
                var definition = mapper.treeToValue(item, FactorDefinition.class);
                if (definitions.putIfAbsent(definition.factorId(), definition) != null)
                    throw new IOException("Duplicate factor id: " + definition.factorId());
            }
            if (definitions.isEmpty()) throw new IOException("Empty factor catalog");
            return new FactorDefinitionCatalog(definitions, sha);
        }
    }

    public List<FactorDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    public FactorDefinition require(String id) {
        var definition = definitions.get(id);
        if (definition == null) throw new IllegalArgumentException("Unknown factor: " + id);
        return definition;
    }

    public String sourceConfigSha256() {
        return sourceConfigSha256;
    }
}
