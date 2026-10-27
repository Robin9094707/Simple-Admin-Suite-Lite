package io.github.robin9094707.simpleadminlite;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class Crypto {
    private Crypto(){}
    public static String hmac(byte[] body,String secret){
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(body));}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    public static String token(String kind,String value,String secret){return kind+":"+hmac((kind+":"+value).getBytes(StandardCharsets.UTF_8),secret);}
    public static boolean valid(byte[] body,String secret,String signature){
        if(signature==null||!signature.matches("[0-9a-f]{64}"))return false;
        return MessageDigest.isEqual(hmac(body,secret).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII));
    }
    public static String secret(){byte[]bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes);}
    public static String sha(byte[] body){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));}catch(Exception e){throw new IllegalStateException(e);}}
}
