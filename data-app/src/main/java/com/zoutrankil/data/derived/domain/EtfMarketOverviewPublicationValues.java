package com.zoutrankil.data.derived.domain;

import com.zoutrankil.data.domain.*;
import java.io.*;

/** Exact original-owner values and canonical bytes without execution dependencies. */
public final class EtfMarketOverviewPublicationValues {
    private EtfMarketOverviewPublicationValues() {}
    public static byte[] canonicalBytes(EtfMarketOverviewCachePublicationEnvelope row){
            try(var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes)){
                out.writeUTF(row.tradeDate().toString());out.writeUTF(row.sourceVersion());out.writeBoolean(row.cache()!=null);
                if(row.cache()!=null){out.writeLong(row.cache().etfCount());number(out,row.cache().totalShare());number(out,row.cache().totalSizeYi());}
                out.writeBoolean(row.receipt()!=null);if(row.receipt()!=null){out.writeUTF(row.receipt().tradeDate().toString());out.writeUTF(row.receipt().datasetId());out.writeUTF(row.receipt().sourceVersion());out.writeLong(row.receipt().rowCount());out.writeUTF(row.receipt().contentDigest());}
                return bytes.toByteArray();
            }catch(IOException error){throw new IllegalArgumentException("Cannot encode exact D101 publication",error);}
        }
    private static void number(DataOutputStream out,Double value)throws IOException{out.writeBoolean(value!=null);if(value!=null)out.writeLong(Double.doubleToRawLongBits(value));}
    public static String fingerprint(EtfMarketOverviewCachePublicationEnvelope envelope){return envelope.sourceFingerprint();}
    public static boolean sameCache(EtfMarketOverviewDailyCache a,EtfMarketOverviewDailyCache b){if(a==null||b==null)return a==b;return a.key().equals(b.key())&&a.etfCount()==b.etfCount()&&sameDouble(a.totalShare(),b.totalShare())&&sameDouble(a.totalSizeYi(),b.totalSizeYi());}
    private static boolean sameDouble(Double a,Double b){if(a==null||b==null)return a==b;return Double.doubleToRawLongBits(a)==Double.doubleToRawLongBits(b);}
}
