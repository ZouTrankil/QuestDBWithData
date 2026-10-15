package com.zoutrankil.data.strategy.score;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FrozenPromotedRecipeTest {
    @TempDir Path temporary;
    @Test void acceptedNativeParquetLoadsScalarWeightsAndKeepsCutoffAndHashIdentity() throws Exception {
        var golden = EtfOnlyTop2ScoreProducerTest.golden(); var cutoff = golden.get("training_cutoff").asText();
        var recipe = new LinkedHashMap<String, Object>();
        recipe.put("strategy_id", "ETF_ONLY_TOP2"); recipe.put("score_recipe_id", "oracle"); recipe.put("effective_from", LocalDate.parse(cutoff).plusDays(1).toString());
        recipe.put("training_cutoff", cutoff); recipe.put("input_schema_version", "etf_only_top2_score_panel_v1"); recipe.put("code_revision", "accepted-revision");
        recipe.put("component_l2_weight", .55); recipe.put("buy_breadth_weight", .20); recipe.put("trend_weight", .20); recipe.put("liquidity_weight", .05);
        recipe.put("factor_weight_artifact", "factor_weights.parquet"); recipe.put("factor_state_artifact", "factor_states.parquet"); recipe.put("l2_factor_weight_artifact", "l2_factor_weights.parquet");
        var hashes = new LinkedHashMap<String, String>();
        for (String role : List.of("factor_weights", "factor_states", "l2_factor_weights")) {
            try (var stream = getClass().getResourceAsStream("/score/" + role + ".parquet")) { Files.copy(Objects.requireNonNull(stream), temporary.resolve(role + ".parquet")); }
            hashes.put(role, FrozenPromotedRecipe.sha256(Files.readAllBytes(temporary.resolve(role + ".parquet"))));
        }
        Path recipePath = temporary.resolve("promoted.json"); Files.write(recipePath, new ObjectMapper().writeValueAsBytes(recipe)); hashes.put("recipe", FrozenPromotedRecipe.sha256(Files.readAllBytes(recipePath)));
        var loaded = FrozenPromotedRecipe.load(recipePath, temporary, hashes);
        assertEquals(LocalDate.parse(cutoff), loaded.recipe().trainingCutoff()); assertEquals(hashes, loaded.artifactSha256());
        assertEquals("frozen-test", loaded.factorStates().getFirst().values().get("state"));
        assertEquals(EtfOnlyTop2ScoreProducerTest.promoted(golden).factorWeights().getFirst().number("w_component_l2"), loaded.factorWeights().getFirst().number("w_component_l2"), 1e-14);
        Files.writeString(temporary.resolve("factor_weights.parquet"), "changed", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> FrozenPromotedRecipe.load(recipePath, temporary, hashes));
    }
    @Test void csvKeepsLastAsOfObservationAndNeverLooksAhead() {
        var rows = FrozenPromotedRecipe.csv("trade_date,weight,state\r\n20260925,0.1,\"quoted, state\"\r\n20260928,0.2,later\r\n20260925,0.3,last\r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(.3, ScoreMath.number(FrozenPromotedRecipe.asOf(rows, LocalDate.of(2026, 9, 25)).get("weight")));
        assertEquals(.3, ScoreMath.number(FrozenPromotedRecipe.asOf(rows, LocalDate.of(2026, 9, 27)).get("weight")));
        assertTrue(FrozenPromotedRecipe.asOf(rows, LocalDate.of(2026, 9, 24)).isEmpty());
    }
    @Test void missingWeightColumnsFutureTrainingOrUnacceptedHashesFailClosed() throws Exception {
        var source = EtfOnlyTop2ScoreProducerTest.promoted(EtfOnlyTop2ScoreProducerTest.golden());
        assertThrows(IllegalArgumentException.class, () -> new FrozenPromotedRecipe(source.recipe(), List.of(new FrozenPromotedRecipe.DatedValues(source.recipe().trainingCutoff(), Map.of("w_component_l2", 1.))), source.factorStates(), source.l2Weights(), source.artifactSha256()));
        assertThrows(IllegalArgumentException.class, () -> new FrozenPromotedRecipe(source.recipe(), source.factorWeights(), List.of(new FrozenPromotedRecipe.DatedValues(source.recipe().trainingCutoff().plusDays(1), Map.of("state", "future"))), source.l2Weights(), source.artifactSha256()));
        assertThrows(IllegalArgumentException.class, () -> new FrozenPromotedRecipe(source.recipe(), source.factorWeights(), source.factorStates(), source.l2Weights(), Map.of()));
    }
}
