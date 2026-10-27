package io.github.robin9094707.simpleadminlite;

import org.bukkit.*;
import org.bukkit.ban.*;
import java.util.*;
import static io.github.robin9094707.simpleadminlite.Model.*;

/** Bukkit's official ban lists perform the actual vanilla write; no hand-edited live JSON files. */
final class VanillaExport {
    record Entry(UUID id,String name,String ip,Rule rule){}
    record Plan(List<Entry>entries,long revision,long created){Plan{entries=List.copyOf(entries);}}
    static Plan preview(Engine engine){
        long now=System.currentTimeMillis();Map<UUID,Profile>targets=new LinkedHashMap<>();for(Profile p:engine.profiles())targets.put(p.id(),p);
        for(Rule r:engine.rules())if(r.kind()==Kind.BAN&&r.target()!=null)targets.putIfAbsent(r.target(),new Profile(r.target(),"",now,now,"UNKNOWN",Map.of(),List.of()));
        List<Entry>out=new ArrayList<>();for(Profile p:targets.values()){
            Set<String>tokens=new HashSet<>();for(Seen seen:p.history())tokens.add(seen.token());Rule denial=engine.denial(p.id(),"",tokens,now);
            if(denial!=null)out.add(new Entry(p.id(),p.name(),"",denial));
        }
        Map<String,Rule>ips=new TreeMap<>();for(Rule r:engine.rules())if(r.kind()==Kind.IP&&r.active(now))ips.merge(r.selector(),r,(a,b)->longer(a,b)?a:b);
        ips.forEach((ip,rule)->out.add(new Entry(null,"",ip,rule)));return new Plan(out,engine.revision(),now);
    }
    private static boolean longer(Rule a,Rule b){return a.until()==0||b.until()!=0&&a.until()>=b.until();}
    private static boolean existingStronger(BanEntry<?> old,Rule rule){return old!=null&&(old.getExpiration()==null||rule.until()!=0&&old.getExpiration().getTime()>=rule.until());}
    static int apply(Plan plan,Engine engine,String actor){
        if(plan.created()<System.currentTimeMillis()-600000||plan.revision()!=engine.revision())throw new IllegalArgumentException("Profile or rule data changed after the preview. Open a fresh export preview.");
        ProfileBanList accounts=Bukkit.getBanList(BanList.Type.PROFILE);IpBanList ips=Bukkit.getBanList(BanList.Type.IP);int count=0;
        for(Entry entry:plan.entries()){
            Rule r=entry.rule();if(!r.active(System.currentTimeMillis()))continue;Date expires=r.until()==0?null:new Date(r.until());
            if(entry.id()!=null){
                // Imported UUID-only records and Floodgate display names are not always valid Java names.
                String name=entry.name().matches("[A-Za-z0-9_]{1,16}")?entry.name():null;
                var profile=Bukkit.createPlayerProfile(entry.id(),name);
                if(existingStronger(accounts.getBanEntry(profile),r))continue;
                accounts.addBan(profile,r.reason(),expires,actor);count++;
            }else{
                if(existingStronger(ips.getBanEntry(entry.ip()),r))continue;ips.addBan(entry.ip(),r.reason(),expires,actor);count++;
            }
        }return count;
    }
}
