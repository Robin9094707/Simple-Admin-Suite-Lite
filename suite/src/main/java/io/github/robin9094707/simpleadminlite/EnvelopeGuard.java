package io.github.robin9094707.simpleadminlite;

import java.util.*;

/** Bounded replay protection for both bridge endpoints. */
final class EnvelopeGuard {
    private final Map<String,Long> nonces=new LinkedHashMap<>();
    synchronized boolean accept(String nonce,long timestamp,long now){
        nonces.entrySet().removeIf(e->e.getValue()<now-90000);
        if(nonce==null||!nonce.matches("[a-zA-Z0-9-]{16,100}")||timestamp<now-60000||timestamp>now+60000||nonces.containsKey(nonce)||nonces.size()>=4096)return false;
        nonces.put(nonce,now);return true;
    }
}
