package io.github.robin9094707.simpleadminlite;

import java.util.*;
import static io.github.robin9094707.simpleadminlite.Model.*;

/** Indexed account intelligence. Shared IPs are hints; Java diagnostics never link identities. */
public final class Engine {
    private final Store store;
    private final Map<UUID,Profile> profiles=new LinkedHashMap<>();
    private final Map<String,Set<UUID>> index=new HashMap<>();
    private final Map<String,Rule> rules=new LinkedHashMap<>();
    private Map<String,String> links;
    private long revision;
    private Map<UUID,Set<UUID>> components=Map.of();
    private boolean graphDirty=true;
    public Engine(Store store) throws Exception {
        this.store=store;for(Profile p:store.profiles())index(p);reloadRules();links=store.links();
    }
    private void index(Profile p) {
        Profile old=profiles.put(p.id(),p);
        Set<String>newTokens=new HashSet<>();for(Seen s:p.history())newTokens.add(s.token());
        Set<String>oldTokens=new HashSet<>();if(old!=null)for(Seen s:old.history())oldTokens.add(s.token());
        if(old!=null&&newTokens.equals(oldTokens))return;
        graphDirty=true;
        if(old!=null)for(Seen s:old.history()){Set<UUID> ids=index.get(s.token());if(ids!=null){ids.remove(p.id());if(ids.isEmpty())index.remove(s.token());}}
        for(Seen s:p.history())index.computeIfAbsent(s.token(),k->new LinkedHashSet<>()).add(p.id());
    }
    public synchronized long revision(){return revision;}
    public synchronized List<Profile> profiles(){return List.copyOf(profiles.values());}
    public synchronized Profile profile(UUID id){return profiles.get(id);}
    public synchronized UUID resolve(String text){
        try{return UUID.fromString(text);}catch(IllegalArgumentException ignored){}
        return profiles.values().stream().filter(p->p.name().equalsIgnoreCase(text)).max(Comparator.comparingLong(Profile::last)).map(Profile::id).orElse(null);
    }
    public synchronized List<Rule> rules(){return List.copyOf(rules.values());}
    public synchronized Rule rule(String id){return rules.get(id);}
    public synchronized void reloadRules() throws Exception {rules.clear();for(Rule r:store.rules())rules.put(r.id(),r);revision++;}
    public synchronized Profile observe(UUID id,String name,String platform,String ip,Map<String,String> metadata,Collection<String> identityTokens,long now,int historyLimit) throws Exception {
        Profile old=profiles.get(id);Map<String,String> client=new LinkedHashMap<>();if(old!=null&&old.platform().equals(platform))client.putAll(old.client());client.putAll(metadata);
        LinkedHashMap<String,Seen> history=new LinkedHashMap<>();if(old!=null)for(Seen s:old.history())history.put(s.token(),s);
        Set<String> tokens=new LinkedHashSet<>(identityTokens);if(ip!=null&&!ip.isBlank())tokens.add("ip:"+Model.ip(ip));
        for(String token:tokens){
            if(!token.matches("(?:ip|device|xuid|self):.{1,180}"))continue;
            Seen seen=history.remove(token);Map<String,String> detail=new LinkedHashMap<>();if(seen!=null)detail.putAll(seen.details());
            if(token.startsWith("device:")){for(String k:List.of("deviceModel","deviceOs","gameVersion","currentInputMode","collectionSource"))if(metadata.containsKey(k))detail.put(k,metadata.get(k));}
            history.put(token,new Seen(token,seen==null?now:seen.first(),now,detail));
        }
        // Bound each history type independently. Device observations cannot evict the IP history.
        for(String prefix:List.of("ip:","device:","xuid:","self:")){
            List<Seen> ofType=history.values().stream().filter(s->s.token().startsWith(prefix)).sorted(Comparator.comparingLong(Seen::last)).toList();
            for(int i=0;i<ofType.size()-Math.max(8,historyLimit);i++)history.remove(ofType.get(i).token());
        }
        Profile p=new Profile(id,clean(name,100),old==null?now:old.first(),now,platform,client,List.copyOf(history.values()));
        boolean changed=old==null||!old.name().equals(p.name())||!new HashSet<>(old.history().stream().map(Seen::token).toList()).equals(new HashSet<>(p.history().stream().map(Seen::token).toList()));
        store.save(p);index(p);if(changed)revision++;return p;
    }
    public synchronized void link(UUID a,UUID b,String type,String actor) throws Exception {
        if(a.equals(b)||!profiles.containsKey(a)||!profiles.containsKey(b))throw new IllegalArgumentException("Choose two different registered profiles");
        if(!Set.of("confirmed","ignored","automatic").contains(type))throw new IllegalArgumentException("Invalid link type");
        store.link(a,b,type,actor);links=store.links();graphDirty=true;revision++;
    }
    public synchronized String linkType(UUID a,UUID b){return links.getOrDefault(Store.pair(a,b),"automatic");}
    private boolean strong(String token){return token.startsWith("device:")||token.startsWith("xuid:");}
    public synchronized Set<UUID> component(UUID id){
        if(graphDirty)rebuildGroups();return components.getOrDefault(id,Set.of(id));
    }
    private void rebuildGroups(){
        Map<UUID,UUID> roots=new HashMap<>();Map<UUID,Set<UUID>> groups=new HashMap<>(),excluded=new HashMap<>();
        for(UUID id:profiles.keySet()){roots.put(id,id);groups.put(id,new LinkedHashSet<>(Set.of(id)));}
        for(var e:links.entrySet())if(e.getValue().equals("ignored")){String[]pair=e.getKey().split("\\|");UUID a=UUID.fromString(pair[0]),b=UUID.fromString(pair[1]);excluded.computeIfAbsent(a,k->new HashSet<>()).add(b);excluded.computeIfAbsent(b,k->new HashSet<>()).add(a);}
        java.util.function.BiConsumer<UUID,UUID> union=(a,b)->{
            UUID one=roots.get(a),two=roots.get(b);if(one==null||two==null||one.equals(two))return;
            Set<UUID>x=groups.get(one),y=groups.get(two);Set<UUID>small=x.size()<y.size()?x:y,large=small==x?y:x;
            for(UUID member:small)for(UUID ignored:excluded.getOrDefault(member,Set.of()))if(large.contains(ignored))return;
            UUID keep=one.compareTo(two)<0?one:two,drop=keep.equals(one)?two:one;Set<UUID>merged=groups.get(keep),removed=groups.remove(drop);merged.addAll(removed);for(UUID member:removed)roots.put(member,keep);
        };
        links.entrySet().stream().filter(e->e.getValue().equals("confirmed")).sorted(Map.Entry.comparingByKey()).forEach(e->{String[]p=e.getKey().split("\\|");union.accept(UUID.fromString(p[0]),UUID.fromString(p[1]));});
        index.entrySet().stream().filter(e->strong(e.getKey())).sorted(Map.Entry.comparingByKey()).forEach(e->{
            List<UUID> representatives=new ArrayList<>();for(UUID id:e.getValue().stream().sorted().toList()){
                boolean merged=false;for(UUID representative:representatives){if(roots.get(id).equals(roots.get(representative))){merged=true;break;}union.accept(id,representative);if(roots.get(id).equals(roots.get(representative))){merged=true;break;}}
                if(!merged)representatives.add(id);
            }
        });
        Map<UUID,Set<UUID>>result=new HashMap<>();for(Set<UUID>group:groups.values()){Set<UUID>immutable=Set.copyOf(group);for(UUID id:group)result.put(id,immutable);}components=Map.copyOf(result);graphDirty=false;
    }
    public synchronized List<Match> matches(UUID id){
        Profile p=profiles.get(id);if(p==null)return List.of();Map<UUID,List<String>> evidence=new LinkedHashMap<>();Set<UUID> group=component(id);
        for(Seen s:p.history())for(UUID other:index.getOrDefault(s.token(),Set.of()))if(!other.equals(id)){
            String label=s.token().startsWith("ip:")?"Shared IP (unconfirmed)":s.token().startsWith("device:")?"Shared reported Bedrock device ID":s.token().startsWith("xuid:")?"Same authenticated Xbox account":"Shared self-signed client ID (unconfirmed)";
            evidence.computeIfAbsent(other,k->new ArrayList<>()).add(label);
        }
        for(UUID other:group)if(!other.equals(id))evidence.computeIfAbsent(other,k->new ArrayList<>()).add(linkType(id,other).equals("confirmed")?"Staff-confirmed account link":"Strong account group (direct or transitive)");
        List<Match> out=new ArrayList<>();for(var e:evidence.entrySet()){
            Profile other=profiles.get(e.getKey());if(other!=null)out.add(new Match(other.id(),other.name(),group.contains(other.id()),e.getValue()));
        }out.sort(Comparator.comparing(Match::strong).reversed().thenComparing(Match::name,String.CASE_INSENSITIVE_ORDER));return List.copyOf(out);
    }
    public synchronized boolean applies(Rule r,UUID id,String currentIp,Set<String> currentTokens,long now){
        if(!r.active(now)||!r.parent().isBlank()&&!rules.containsKey(r.parent()))return false;
        if(r.kind()==Kind.IP)return r.selector().equals(currentIp);
        if(r.kind()==Kind.DEVICE)return currentTokens.contains(r.selector());
        if(r.target()==null)return false;
        if(r.target().equals(id))return true;
        if(r.inherit()&&component(r.target()).contains(id))return true;
        if(r.kind()==Kind.BAN&&r.value().equals("ultra")){
            Profile source=profiles.get(r.target());return source!=null&&source.tokens("device").stream().anyMatch(currentTokens::contains);
        }
        return false;
    }
    public synchronized Rule denial(UUID id,String ip,Set<String> currentTokens,long now){
        return rules.values().stream().filter(r->Set.of(Kind.BAN,Kind.IP,Kind.DEVICE).contains(r.kind())&&applies(r,id,ip,currentTokens,now))
            .max(Comparator.comparingLong(r->r.until()==0?Long.MAX_VALUE:r.until())).orElse(null);
    }
    public synchronized List<Rule> effective(UUID id,long now){
        Set<UUID> group=component(id);
        return rules.values().stream().filter(r->r.active(now)&&r.target()!=null&&(r.target().equals(id)||r.inherit()&&group.contains(r.target())))
            .sorted(Comparator.comparingLong(Rule::created)).toList();
    }
    public synchronized void add(Rule r) throws Exception {store.add(r);rules.put(r.id(),r);revision++;}
    public synchronized void revoke(String id,String actor) throws Exception {store.revoke(id,actor);reloadRules();}
    public synchronized int importRows(List<ImportRow> rows,String actor) throws Exception {int count=store.importRows(rows,actor);reloadRules();return count;}
    /** A concrete active IP ban may learn a Bedrock device. No inference from IP hints is used. */
    public synchronized boolean learnDeviceBan(Rule parent,UUID id,String token,long now) throws Exception {
        if(parent.kind()!=Kind.IP||!parent.active(now)||!rules.containsKey(parent.id())||!token.startsWith("device:"))return false;
        if(rules.values().stream().anyMatch(r->r.parent().equals(parent.id())&&r.selector().equals(token)))return false;
        add(new Rule(UUID.randomUUID().toString(),Kind.DEVICE,id,token,"ip-learned",now,parent.until(),false,parent.reason(),"IP ban device binding",parent.id()));return true;
    }
}
