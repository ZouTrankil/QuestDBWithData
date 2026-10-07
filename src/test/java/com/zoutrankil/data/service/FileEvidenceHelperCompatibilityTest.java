package com.zoutrankil.data.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileEvidenceHelperCompatibilityTest {
    @TempDir Path temporary;

    @Test void catalogAndMembershipKeepTheirOwnBoundFailures() throws Exception {
        var path=Files.write(temporary.resolve("bytes.bin"),new byte[]{1,2});
        assertEquals("Catalog input exceeds byte bound",assertThrowsExactly(IllegalArgumentException.class,
                ()->IndexCatalogFileSource.readBounded(path,1)).getMessage());
        assertEquals("Bounded catalog input limit required",assertThrowsExactly(IllegalArgumentException.class,
                ()->IndexCatalogFileSource.readBounded(path,0)).getMessage());
        assertEquals("Membership evidence exceeds bound",assertThrowsExactly(IllegalArgumentException.class,
                ()->IndexMembershipSourceEvidence.bounded(path,1)).getMessage());
        assertArrayEquals(new byte[]{1,2},IndexMembershipSourceEvidence.bounded(path,2));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                IndexMembershipSourceEvidence.hash("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void ownerGatewayKeepsTheRegularFilePrecheckAndRawDigest() throws Exception {
        var absent=temporary.resolve("absent.json");
        assertEquals("Bounded regular JSON artifact required",assertThrowsExactly(IOException.class,
                ()->EtfMarketOverviewCacheOwnerGateway.readBounded(absent)).getMessage());
        var oversized=Files.write(temporary.resolve("oversized.json"),
                new byte[EtfMarketOverviewCacheOwnerGateway.MAX_JSON_BYTES+1]);
        assertEquals("Bounded regular JSON artifact required",assertThrowsExactly(IOException.class,
                ()->EtfMarketOverviewCacheOwnerGateway.readBounded(oversized)).getMessage());
        var bytes="abc".getBytes(StandardCharsets.UTF_8);var path=Files.write(temporary.resolve("raw.json"),bytes);
        assertArrayEquals(bytes,EtfMarketOverviewCacheOwnerGateway.readBounded(path));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                EtfMarketOverviewCacheOwnerGateway.sha(bytes));
    }
}
