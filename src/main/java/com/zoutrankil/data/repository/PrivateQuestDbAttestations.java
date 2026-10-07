package com.zoutrankil.data.repository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Fixed production attestation factories; each returned check retains its own process history. */
public final class PrivateQuestDbAttestations {
    private PrivateQuestDbAttestations() {}

    @FunctionalInterface
    public interface Attestation { void attest(); }

    @FunctionalInterface
    public interface FixtureCheck { void verify(Path realRoot) throws IOException; }

    public static Attestation marketBreadthV1() {
        var attestor = new PrivateQuestDbInstanceAttestor(
                new PrivateQuestDbInstanceAttestor.Spec("D095", Path.of("var"),
                        Path.of("var", "d095-isolated-questdb"), 18812, 19000), null);
        return attestor::attest;
    }

    public static Attestation retailSentimentV1(FixtureCheck fixtureCheck) {
        Objects.requireNonNull(fixtureCheck);
        var attestor = new PrivateQuestDbInstanceAttestor(
                new PrivateQuestDbInstanceAttestor.Spec("D098", Path.of("var"),
                        Path.of("var", "d098-isolated-questdb"), 18822, 19010), fixtureCheck::verify);
        return attestor::attest;
    }
}
