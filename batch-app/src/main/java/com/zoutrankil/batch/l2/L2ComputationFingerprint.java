package com.zoutrankil.batch.l2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoutrankil.batch.DfcfCsvParser;
import com.zoutrankil.data.domain.DatasetDefinition;
import com.zoutrankil.data.domain.L2DailyFeatureField;
import com.zoutrankil.data.domain.L2DailyFeatures;
import com.zoutrankil.data.domain.L2DailyFeaturesKey;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import static com.zoutrankil.batch.l2.L2FeatureFiles.digest;
import static com.zoutrankil.batch.l2.L2FeatureFiles.hex;
import static com.zoutrankil.batch.l2.L2FeatureFiles.transferDigest;
import static com.zoutrankil.batch.l2.L2FeatureFiles.update;

final class L2ComputationFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHECKPOINT_VERSION = "l2-native-batch-v1";
    private L2ComputationFingerprint() {
    }

    static String computeFingerprint() throws IOException {
        var classes = new TreeMap<String, Class<?>>();
        for (Class<?> type : List.of(L2DailyFeatureBatchService.class, L2DailyFeatureSingleService.class, L2DailyFeatureBatchRequest.class, L2DailyFeatureSingleRequest.class,
            L2DailyFeatureCli.class, L2DailyFeatureSource.class, L2DailyFeatureFileSource.class, L2CheckpointStore.class, L2FileCheckpointStore.class,
            L2BatchState.class, L2FeatureFiles.class, L2BatchExecutionSession.class, L2PublicationGate.class, L2ComputationFingerprint.class, L2DailyFeatureBatchCli.class, L2DailyFeaturePipeline.class,
            DfcfCsvParser.class, L2DailyFeatureField.class, L2DailyFeatures.class,
            L2DailyFeaturesKey.class, DatasetDefinition.StorageType.class, L2FeatureData.class,
            L2FeatureMath.class, L2NumpySort.class, L2WideFeatures.class, L2SpoofingFeatures.class,
            L2QualityFeatures.class, L2LifecycleFeatures.class, L2IntradayFeatures.class,
            L2TradeSignFeatures.class, L2LobTransitionFeatures.class, L2MicrostructureFeatures.class,
            L2GmmFeatures.class, L2IntensityFeatures.class)) collectClasses(type, classes);
        try {
            collectClasses(Class.forName("com.zoutrankil.batch.DfcfCsvInspector"), classes);
        }
        catch (ClassNotFoundException absent) {
            throw new IOException("Missing production CSV reader", absent);
        }
        MessageDigest digest = digest();
        update(digest, CHECKPOINT_VERSION + "\n" + System.getProperty("java.version") + "\n" + JSON.version() + "\n");
        for (var entry : classes.entrySet()) {
            update(digest, entry.getKey() + "\n");
            String resource = "/" + entry.getKey().replace('.', '/') + ".class";
            try (InputStream input = entry.getValue().getResourceAsStream(resource)) {
                if (input == null) throw new IOException("Cannot fingerprint loaded computation class: " + resource);
                transferDigest(input, digest);
            }
        }
        return hex(digest);
    }

    private static void collectClasses(Class<?> type, Map<String, Class<?>> classes) throws IOException {
        if (classes.putIfAbsent(type.getName(), type) != null) return;
        for (Class<?> nested : type.getDeclaredClasses()) collectClasses(nested, classes);
        // Reflection excludes javac's enum-switch helpers and anonymous/local classes.
        // Hash their numbered class files as well: changing only a switch mapping must invalidate resume.
        for (int index = 1;; index++) {
            String name = type.getName() + "$" + index;
            String resource = "/" + name.replace('.', '/') + ".class";
            if (type.getResource(resource) == null) break;
            try {
                collectClasses(Class.forName(name, false, type.getClassLoader()), classes);
            }
            catch (ClassNotFoundException absent) {
                throw new IOException("Cannot fingerprint computation helper: " + name, absent);
            }
        }
    }
}
