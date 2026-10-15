package com.zoutrankil.data.l2live.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Objects;

/** Durable capture identity refers to one received envelope, never a guessed business-event identity. */
public record RawCapture(String captureId,long sequence,String sessionId,String topic,String payloadBase64,
        Instant recvTs,LocalDate captureDate,int qos,boolean retained,boolean duplicate,boolean subscriptionAuthorized) {
    public static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
    public static final int MAX_PAYLOAD_BYTES=1_048_576;
    /** Compatibility constructor for explicit offline captures; live capture supplies its observed SUBACK proof. */
    public RawCapture(String captureId,long sequence,String sessionId,String topic,String payloadBase64,
            Instant recvTs,LocalDate captureDate,int qos,boolean retained,boolean duplicate) {
        this(captureId,sequence,sessionId,topic,payloadBase64,recvTs,captureDate,qos,retained,duplicate,true);
    }
    public RawCapture {
        requireIdentity(captureId,"captureId");requireIdentity(sessionId,"sessionId");
        Objects.requireNonNull(topic,"topic");Objects.requireNonNull(payloadBase64,"payloadBase64");
        Objects.requireNonNull(recvTs,"recvTs");Objects.requireNonNull(captureDate,"captureDate");
        if(sequence<0||qos<0||qos>2||topic.length()>256||topic.indexOf('\0')>=0)
            throw new IllegalArgumentException("Invalid raw envelope bounds");
        if(!captureDate.equals(recvTs.atZone(SHANGHAI).toLocalDate()))
            throw new IllegalArgumentException("Capture date must be the Shanghai receive date");
        decode(payloadBase64);
    }
    public byte[] payloadBytes(){return decode(payloadBase64);}
    private static byte[] decode(String text) {
        if(text.length()>((MAX_PAYLOAD_BYTES+2)/3)*4)throw new IllegalArgumentException("Raw payload exceeds its bound");
        byte[] decoded=Base64.getDecoder().decode(text);
        if(decoded.length>MAX_PAYLOAD_BYTES||!Base64.getEncoder().encodeToString(decoded).equals(text))
            throw new IllegalArgumentException("Canonical base64 payload required");
        return decoded;
    }
    private static void requireIdentity(String value,String name) {
        if(value==null||!value.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("Bounded "+name+" required");
    }
}
