package com.zoutrankil.batch.l2;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * The datetime64 quicksort tie order used by pandas sort_values with its default kind.
 * Mirrors NumPy 2.2.6 aquicksort_datetime, rather than the SIMD integer argsort.
 * Source: https://github.com/numpy/numpy/blob/v2.2.6/numpy/_core/src/npysort/quicksort.cpp
 *
 * Copyright (c) 2005-2024, NumPy Developers. All rights reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * - Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * - Neither the name of the NumPy Developers nor the names of any contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
public final class L2NumpySort {
    private L2NumpySort() {}
    public static <T> List<T> byTime(List<T> rows,ToLongFunction<T> time) {
        long[] keys=rows.stream().mapToLong(time).toArray();int[] index=argsort(keys);
        var sorted=new ArrayList<T>(rows.size());for(int ix:index)sorted.add(rows.get(ix));return sorted;
    }
    public static int[] argsort(long[] keys) {
        int[] index=new int[keys.length];for(int i=0;i<index.length;i++)index[i]=i;
        if(keys.length<2)return index;
        int[] leftStack=new int[128],rightStack=new int[128],depthStack=new int[128];int stack=0;
        int left=0,right=keys.length-1,depth=(31-Integer.numberOfLeadingZeros(keys.length))*2;
        while(true) {
            if(depth<0)heap(keys,index,left,right);
            else {
                while(right-left>15) {
                    int middle=left+((right-left)>>>1);
                    if(keys[index[middle]]<keys[index[left]])swap(index,middle,left);
                    if(keys[index[right]]<keys[index[middle]])swap(index,right,middle);
                    if(keys[index[middle]]<keys[index[left]])swap(index,middle,left);
                    long pivot=keys[index[middle]];int low=left,high=right-1;swap(index,middle,high);
                    while(true) {
                        do {low++;}while(keys[index[low]]<pivot);
                        do {high--;}while(pivot<keys[index[high]]);
                        if(low>=high)break;swap(index,low,high);
                    }
                    swap(index,low,right-1);depth--;
                    if(low-left<right-low) {
                        leftStack[stack]=low+1;rightStack[stack]=right;right=low-1;
                    } else {
                        leftStack[stack]=left;rightStack[stack]=low-1;left=low+1;
                    }
                    depthStack[stack++]=depth;
                }
                for(int i=left+1;i<=right;i++) {
                    int value=index[i],j=i;while(j>left&&keys[value]<keys[index[j-1]]){index[j]=index[j-1];j--;}
                    index[j]=value;
                }
            }
            if(stack==0)break;stack--;left=leftStack[stack];right=rightStack[stack];depth=depthStack[stack];
        }
        return index;
    }
    private static void swap(int[] rows,int a,int b){int value=rows[a];rows[a]=rows[b];rows[b]=value;}
    private static void heap(long[] keys,int[] rows,int left,int right) {
        int n=right-left+1;
        for(int root=n/2;root>0;root--)sift(keys,rows,left,root,n,rows[left+root-1]);
        while(n>1){int value=rows[left+n-1];rows[left+n-1]=rows[left];n--;sift(keys,rows,left,1,n,value);}
    }
    private static void sift(long[] keys,int[] rows,int left,int root,int n,int value) {
        int at=root,child=root*2;
        while(child<=n) {
            if(child<n&&keys[rows[left+child-1]]<keys[rows[left+child]])child++;
            if(keys[value]>=keys[rows[left+child-1]])break;
            rows[left+at-1]=rows[left+child-1];at=child;child*=2;
        }
        rows[left+at-1]=value;
    }
}
