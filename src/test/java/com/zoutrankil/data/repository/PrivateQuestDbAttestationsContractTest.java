package com.zoutrankil.data.repository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The public bridge never exposes the process seam or shares successful PID history. */
class PrivateQuestDbAttestationsContractTest {
    @Test void fixedFactoriesCreateOneIndependentAttestorPerSessionAndDelegateEveryCheck() {
        var specs = new ArrayList<PrivateQuestDbInstanceAttestor.Spec>();
        try (var constructors = mockConstruction(PrivateQuestDbInstanceAttestor.class,
                (attestor, context) -> specs.add((PrivateQuestDbInstanceAttestor.Spec) context.arguments().getFirst()))) {
            var first = PrivateQuestDbAttestations.marketBreadthV1();
            var second = PrivateQuestDbAttestations.marketBreadthV1();
            var third = PrivateQuestDbAttestations.retailSentimentV1(root -> fail("Construction must not read the fixture"));
            assertEquals(3, constructors.constructed().size());
            constructors.constructed().forEach(attestor -> verifyNoInteractions(attestor));
            first.attest();first.attest();second.attest();third.attest();
            verify(constructors.constructed().get(0), times(2)).attest();
            verify(constructors.constructed().get(1)).attest();
            verify(constructors.constructed().get(2)).attest();
            assertEquals(new PrivateQuestDbInstanceAttestor.Spec("D095", Path.of("var"),
                    Path.of("var", "d095-isolated-questdb"), 18812, 19000), specs.get(0));
            assertEquals(specs.get(0), specs.get(1));
            assertEquals(new PrivateQuestDbInstanceAttestor.Spec("D098", Path.of("var"),
                    Path.of("var", "d098-isolated-questdb"), 18822, 19010), specs.get(2));
        }
    }

    @Test void retailCallbackKeepsTheOriginalPathAndIOExceptionIdentity() throws Exception {
        var callbacks = new ArrayList<PrivateQuestDbInstanceAttestor.RootCheck>();
        var visited = new ArrayList<Path>();
        var failure = new IOException("fixture failure");
        Path expected = Path.of("fixture");
        try (var constructors = mockConstruction(PrivateQuestDbInstanceAttestor.class,
                (attestor, context) -> callbacks.add((PrivateQuestDbInstanceAttestor.RootCheck) context.arguments().get(1)))) {
            PrivateQuestDbAttestations.retailSentimentV1(root -> {visited.add(root);throw failure;});
            assertTrue(visited.isEmpty());
            assertSame(failure, assertThrows(IOException.class, () -> callbacks.getFirst().verify(expected)));
            assertEquals(List.of(expected), visited);
            verifyNoInteractions(constructors.constructed().getFirst());
        }
    }

    @Test void retailFactoryCannotOmitItsFixtureCallback() {
        try (var constructors = mockConstruction(PrivateQuestDbInstanceAttestor.class)) {
            assertThrows(NullPointerException.class, () -> PrivateQuestDbAttestations.retailSentimentV1(null));
            assertTrue(constructors.constructed().isEmpty());
        }
    }
}
