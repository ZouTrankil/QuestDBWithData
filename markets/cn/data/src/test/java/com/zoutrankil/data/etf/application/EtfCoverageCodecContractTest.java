package com.zoutrankil.data.etf.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zoutrankil.data.domain.fund.EtfPortfolio;
import com.zoutrankil.data.domain.fund.EtfPortfolioKey;
import com.zoutrankil.data.domain.fund.EtfShare;
import com.zoutrankil.data.domain.fund.EtfShareKey;
import com.zoutrankil.data.service.VerifiedBatchExecutor;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

class EtfCoverageCodecContractTest {
    private static final LocalDate DATE = LocalDate.of(2020, 1, 4);

    @Test
    @SuppressWarnings("unchecked")
    void portfolioComparisonUsesProvidedBytesAndRejectsDuplicateKeysOnEitherSide() {
        VerifiedBatchExecutor.Codec<EtfPortfolio, EtfPortfolioKey> codec =
                mock(VerifiedBatchExecutor.Codec.class);
        var expected = mock(EtfPortfolio.class);
        var actual = mock(EtfPortfolio.class);
        var second = mock(EtfPortfolio.class);
        var key = new EtfPortfolioKey("510300.SH", DATE, DATE.minusDays(4), "600000.SH");
        when(expected.key()).thenReturn(key);
        when(actual.key()).thenReturn(key);
        when(second.key())
                .thenReturn(new EtfPortfolioKey("510300.SH", DATE, DATE.minusDays(4), "600001.SH"));
        when(codec.canonicalBytes(expected)).thenReturn(new byte[] {1, 2});
        when(codec.canonicalBytes(actual)).thenReturn(new byte[] {1, 2});
        when(codec.canonicalBytes(second)).thenReturn(new byte[] {3});
        assertTrue(EtfPortfolioCoverage.sameRows(List.of(expected), List.of(actual), codec));
        assertFalse(
                EtfPortfolioCoverage.sameRows(
                        List.of(expected, expected), List.of(actual, second), codec));
        assertFalse(
                EtfPortfolioCoverage.sameRows(
                        List.of(expected, second), List.of(actual, actual), codec));
        when(codec.canonicalBytes(actual)).thenReturn(new byte[] {1, 3});
        assertFalse(EtfPortfolioCoverage.sameRows(List.of(expected), List.of(actual), codec));
        clearInvocations(codec);
        assertFalse(EtfPortfolioCoverage.sameRows(List.of(expected), List.of(), codec));
        verifyNoInteractions(codec);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shareComparisonUsesProvidedBytesAndRejectsDuplicateKeysOnEitherSide() {
        VerifiedBatchExecutor.Codec<EtfShare, EtfShareKey> codec =
                mock(VerifiedBatchExecutor.Codec.class);
        var expected = mock(EtfShare.class);
        var actual = mock(EtfShare.class);
        var second = mock(EtfShare.class);
        var key = new EtfShareKey("510300.SH", DATE);
        when(expected.key()).thenReturn(key);
        when(actual.key()).thenReturn(key);
        when(second.key()).thenReturn(new EtfShareKey("510500.SH", DATE));
        when(codec.canonicalBytes(expected)).thenReturn(new byte[] {1, 2});
        when(codec.canonicalBytes(actual)).thenReturn(new byte[] {1, 2});
        when(codec.canonicalBytes(second)).thenReturn(new byte[] {3});
        assertTrue(EtfShareCoverage.sameRows(List.of(expected), List.of(actual), codec));
        assertFalse(
                EtfShareCoverage.sameRows(
                        List.of(expected, expected), List.of(actual, second), codec));
        assertFalse(
                EtfShareCoverage.sameRows(
                        List.of(expected, second), List.of(actual, actual), codec));
        when(codec.canonicalBytes(actual)).thenReturn(new byte[] {1, 3});
        assertFalse(EtfShareCoverage.sameRows(List.of(expected), List.of(actual), codec));
        clearInvocations(codec);
        assertFalse(EtfShareCoverage.sameRows(List.of(expected), List.of(), codec));
        verifyNoInteractions(codec);
    }
}
