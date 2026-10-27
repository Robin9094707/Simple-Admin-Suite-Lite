package io.github.robin9094707.simpleadminlite;

import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Immutable values shared by persistence, enforcement and both menu implementations. */
public final class Model {
    private Model() {}
    public enum Kind { BAN, IP, DEVICE, PERMISSION, TEMPLATE, ROLE }
    public record Seen(String token, long first, long last, Map<String,String> details) {
        public Seen { details = Map.copyOf(details); }
    }
    public record Profile(UUID id, String name, long first, long last, String platform,
                          Map<String,String> client, List<Seen> history) {
        public Profile { client = Map.copyOf(client); history = List.copyOf(history); }
        public Set<String> tokens(String prefix) {
            Set<String> result = new LinkedHashSet<>();
            for (Seen seen: history) if (seen.token().startsWith(prefix+":")) result.add(seen.token());
            return Set.copyOf(result);
        }
    }
    public record Rule(String id, Kind kind, UUID target, String selector, String value,
                       long created, long until, boolean inherit, String reason, String actor, String parent) {
        public Rule {
            Objects.requireNonNull(id); Objects.requireNonNull(kind);
            selector = Objects.requireNonNullElse(selector, ""); value = Objects.requireNonNullElse(value, "");
            reason = clean(reason, 1024); actor = clean(actor, 100); parent = Objects.requireNonNullElse(parent, "");
            if (until < 0) throw new IllegalArgumentException("Invalid expiry");
        }
        public boolean active(long now) { return until == 0 || until > now; }
        public static Rule make(Kind kind, UUID target, String selector, String value, long until,
                                boolean inherit, String reason, String actor) {
            return new Rule(UUID.randomUUID().toString(),kind,target,selector,value,System.currentTimeMillis(),until,inherit,reason,actor,"");
        }
    }
    public record Match(UUID id, String name, boolean strong, List<String> evidence) {
        public Match { evidence = List.copyOf(evidence); }
    }
    public record ImportRow(String key, Rule rule, String label, String warning) {}
    public static String clean(String value, int limit) {
        String text = Objects.requireNonNullElse(value, "").replaceAll("[\\p{Cntrl}§]", " ").strip();
        return text.substring(0, Math.min(text.length(),limit));
    }
    public static String ip(String value) {
        String s = Objects.requireNonNullElse(value, "").strip();
        if (!s.matches("[0-9a-fA-F:.]{2,45}") || s.indexOf('.') < 0 && s.indexOf(':') < 0)
            throw new IllegalArgumentException("An exact IPv4 or IPv6 address is required; hostnames and wildcards are not supported.");
        if (s.indexOf(':') < 0) {
            String[] pieces=s.split("\\.",-1);
            if(pieces.length!=4) throw new IllegalArgumentException("Invalid IPv4 address");
            for(String piece:pieces) if(!piece.matches("[0-9]{1,3}") || Integer.parseInt(piece)>255)
                throw new IllegalArgumentException("Invalid IPv4 address");
        }
        try { return InetAddress.getByName(s).getHostAddress(); }
        catch(Exception e) { throw new IllegalArgumentException("Invalid IP address"); }
    }
    /** Zero means permanent. Overflow and accidental already-expired durations are rejected. */
    public static long expiry(String input, long now) {
        String s=input.strip().toLowerCase(Locale.ROOT);
        if(Set.of("permanent","perm","forever","0").contains(s)) return 0;
        Matcher m=Pattern.compile("([1-9][0-9]{0,8})([smhdw])").matcher(s);
        if(!m.matches()) throw new IllegalArgumentException("Use 30m, 1h, 7d, 4w or permanent.");
        long unit=switch(m.group(2)){case "s"->1000;case "m"->60000;case "h"->3600000;case "d"->86400000;default->604800000;};
        try { return Math.addExact(now,Math.multiplyExact(Long.parseLong(m.group(1)),unit)); }
        catch(ArithmeticException e) { throw new IllegalArgumentException("Duration is too large"); }
    }
    public static String when(long millis) { return millis==0?"Permanent":Instant.ofEpochMilli(millis).toString(); }
    public static String node(String value) {
        String s=value.strip().toLowerCase(Locale.ROOT);
        if(!s.matches("[a-z0-9_*][a-z0-9_.:*/-]{0,179}")) throw new IllegalArgumentException("Invalid permission node");
        return s;
    }
    static byte[] encode(Properties p) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); p.store(out,"Simple Admin Suite Lite"); return out.toByteArray();
    }
    static Properties decode(byte[] bytes) throws IOException {
        Properties p=new Properties(); p.load(new ByteArrayInputStream(bytes)); return p;
    }
    static byte[] profileBytes(Profile p) throws IOException {
        Properties out=new Properties();out.setProperty("id",p.id().toString());out.setProperty("name",p.name());
        out.setProperty("first",""+p.first());out.setProperty("last",""+p.last());out.setProperty("platform",p.platform());
        p.client().forEach((k,v)->out.setProperty("client."+k,v));
        for(int i=0;i<p.history().size();i++){
            Seen s=p.history().get(i);String b="history."+i+".";out.setProperty(b+"token",s.token());
            out.setProperty(b+"first",""+s.first());out.setProperty(b+"last",""+s.last());s.details().forEach((k,v)->out.setProperty(b+"detail."+k,v));
        }
        out.setProperty("count",""+p.history().size());return encode(out);
    }
    static Profile profile(byte[] bytes) throws IOException {
        Properties p=decode(bytes);Map<String,String> client=new LinkedHashMap<>();List<Seen> history=new ArrayList<>();
        for(String k:p.stringPropertyNames()) if(k.startsWith("client.")) client.put(k.substring(7),p.getProperty(k));
        for(int i=0;i<Integer.parseInt(p.getProperty("count","0"));i++){
            String b="history."+i+".";Map<String,String>d=new LinkedHashMap<>();
            for(String k:p.stringPropertyNames())if(k.startsWith(b+"detail."))d.put(k.substring((b+"detail.").length()),p.getProperty(k));
            history.add(new Seen(p.getProperty(b+"token"),Long.parseLong(p.getProperty(b+"first")),Long.parseLong(p.getProperty(b+"last")),d));
        }
        return new Profile(UUID.fromString(p.getProperty("id")),p.getProperty("name"),Long.parseLong(p.getProperty("first")),Long.parseLong(p.getProperty("last")),p.getProperty("platform"),client,history);
    }
}
