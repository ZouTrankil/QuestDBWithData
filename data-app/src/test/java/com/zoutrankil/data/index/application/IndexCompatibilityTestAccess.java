package com.zoutrankil.data.index.application;

import java.nio.file.Path;

/** Cross-family tests retain access to package-private helpers without widening production APIs. */
public final class IndexCompatibilityTestAccess {
    private IndexCompatibilityTestAccess() {}

    public static byte[] catalogBounded(Path path, int max) throws Exception {
        return IndexCatalogFileSource.readBounded(path, max);
    }

    public static byte[] membershipBounded(Path path, int max) throws Exception {
        return IndexMembershipSourceEvidence.bounded(path, max);
    }

    public static String membershipHash(byte[] bytes) throws Exception {
        return IndexMembershipSourceEvidence.hash(bytes);
    }

    public static boolean dcHasLedger(Path path) throws Exception {
        return DcIndexCoverage.hasLedger(path);
    }
}
