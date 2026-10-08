package com.zoutrankil.batch.l2;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class L2NumpySortTest {
    @Test void duplicateTimestampPermutationMatchesInstalledNumpyDatetimeQuicksort() {
        long[] keys=new long[32];for(int i=0;i<keys.length;i++)keys[i]=i/4;
        // Captured from NumPy 2.2.6 argsort(keys.astype('datetime64[ms]'),kind='quicksort').
        assertArrayEquals(new int[]{0,1,2,3,4,5,6,7,8,9,10,11,14,15,12,13,16,17,18,19,23,22,20,21,24,25,26,27,30,28,29,31},
                L2NumpySort.argsort(keys));
    }
    @Test void emptySingletonAndDescendingTimesProduceCompletePermutations() {
        assertArrayEquals(new int[]{},L2NumpySort.argsort(new long[]{}));
        assertArrayEquals(new int[]{0},L2NumpySort.argsort(new long[]{42}));
        assertArrayEquals(new int[]{3,2,1,0},L2NumpySort.argsort(new long[]{4,3,2,1}));
    }
}
