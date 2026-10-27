package io.github.robin9094707.simpleadminlite;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static io.github.robin9094707.simpleadminlite.Model.*;

/** One navigation model rendered as protected Java inventories or native Cumulus forms. */
final class Menus implements Listener {
    record Button(String title,String detail,Material material,String icon,Runnable action){}
    static final class View implements InventoryHolder {
        final UUID owner;final long generation;final Map<Integer,Button>actions=new HashMap<>();Inventory inventory;
        View(UUID owner,long generation){this.owner=owner;this.generation=generation;}
        @Override public Inventory getInventory(){return inventory;}
    }
    record Prompt(Player player,long generation,String title,List<String>labels,List<String>defaults,List<String>answers,int index,long expires,Consumer<List<String>>done){}
    static final class Selection {
        final BanTransfer.Preview preview;final Set<String>selected=new HashSet<>();Selection(BanTransfer.Preview preview){this.preview=preview;}
    }
    private final SimpleAdminLite plugin;
    private final Map<UUID,Long>generations=new HashMap<>();
    private final ConcurrentMap<UUID,Prompt>prompts=new ConcurrentHashMap<>();
    private final Map<UUID,Selection>imports=new HashMap<>();
    Menus(SimpleAdminLite plugin){this.plugin=plugin;}
    private long next(Player p){prompts.remove(p.getUniqueId());return generations.merge(p.getUniqueId(),1L,Long::sum);}
    void callback(Player p,long generation,Runnable action){
        if(!p.isOnline()||Bukkit.getPlayer(p.getUniqueId())!=p||generations.getOrDefault(p.getUniqueId(),0L)!=generation||!plugin.require(p,"access"))return;
        generations.put(p.getUniqueId(),generation+1);
        try{action.run();}catch(IllegalArgumentException error){plugin.tell(p,error.getMessage());home(p);}
    }
    private Button button(Player p,String title,String detail,Material material,String permission,Runnable action){
        return new Button(title,detail,material,icon(material),()->{if(permission.isBlank()||plugin.require(p,permission))action.run();});
    }
    private static String icon(Material m){return switch(m){case PLAYER_HEAD->"textures/ui/glyph_skin_pack";case IRON_BARS,BARRIER->"textures/ui/Caution";case CLOCK->"textures/ui/clock";case SPYGLASS->"textures/ui/magnifyingGlass";case SHIELD->"textures/ui/permissions_op_crown";case OAK_DOOR->"textures/ui/chevron_left";case LIME_DYE->"textures/ui/confirm";case WRITABLE_BOOK,BOOK->"textures/ui/book_edit_default";default->"textures/ui/icon_setting";};}
    private void show(Player p,String title,String body,List<Button>items,int page,Runnable back){
        if(!plugin.require(p,"access"))return;
        long generation=next(p);boolean bedrock=plugin.crossplay.bedrock(p.getUniqueId());int limit=bedrock?12:28;
        int pages=Math.max(1,(items.size()+limit-1)/limit),at=Math.max(0,Math.min(page,pages-1));List<Button>visible=new ArrayList<>(items.subList(Math.min(at*limit,items.size()),Math.min((at+1)*limit,items.size())));
        String heading=title+(pages>1?" "+(at+1)+"/"+pages:"");
        List<Button>nav=new ArrayList<>();
        if(at>0)nav.add(button(p,"Previous page","Page "+at,Material.ARROW,"",()->show(p,title,body,items,at-1,back)));
        if(at+1<pages)nav.add(button(p,"Next page","Page "+(at+2),Material.ARROW,"",()->show(p,title,body,items,at+1,back)));
        nav.add(button(p,"Back","Return to the previous menu",Material.OAK_DOOR,"",back));
        nav.add(button(p,"Home","Security dashboard",Material.NETHER_STAR,"",()->home(p)));
        nav.add(button(p,"Close","Close this menu",Material.BARRIER,"",()->{p.closeInventory();next(p);}));
        if(bedrock){List<Button>form=new ArrayList<>(visible);form.addAll(nav);if(plugin.crossplay.menu(p,heading,body,form,generation))return;}
        View holder=new View(p.getUniqueId(),generation);holder.inventory=Bukkit.createInventory(holder,54,"§8"+heading.substring(0,Math.min(heading.length(),30)));
        ItemStack glass=new ItemStack(Material.GRAY_STAINED_GLASS_PANE);ItemMeta gm=glass.getItemMeta();gm.setDisplayName(" ");glass.setItemMeta(gm);
        for(int i=0;i<54;i++)holder.inventory.setItem(i,glass);
        holder.inventory.setItem(4,item(new Button(title,body,Material.SHIELD,icon(Material.SHIELD),()->{})));
        for(int i=0;i<visible.size();i++){int slot=10+(i/7)*9+i%7;put(holder,slot,visible.get(i));}
        int slot=45;for(Button b:nav)put(holder,slot++,b);
        p.openInventory(holder.inventory);
    }
    private void put(View view,int slot,Button b){view.actions.put(slot,b);view.inventory.setItem(slot,item(b));}
    private ItemStack item(Button b){
        ItemStack stack=new ItemStack(b.material());ItemMeta meta=stack.getItemMeta();meta.setDisplayName("§f"+b.title());List<String>lore=new ArrayList<>();
        for(String line:b.detail().split("\n"))for(int at=0;at<Math.max(1,line.length());at+=58)lore.add("§f"+line.substring(at,Math.min(line.length(),at+58)));
        meta.setLore(lore);meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);stack.setItemMeta(meta);return stack;
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void click(InventoryClickEvent e){
        if(!(e.getView().getTopInventory().getHolder() instanceof View v))return;e.setCancelled(true);
        if(e.getWhoClicked() instanceof Player p&&v.owner.equals(p.getUniqueId())){Button b=v.actions.get(e.getRawSlot());if(b!=null&&e.getClick()!=ClickType.DOUBLE_CLICK)callback(p,v.generation,b.action());}
    }
    @EventHandler(priority=EventPriority.HIGHEST)public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof View)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST)public void move(InventoryMoveItemEvent e){if(e.getSource().getHolder() instanceof View||e.getDestination().getHolder() instanceof View)e.setCancelled(true);}
    void input(Player p,String title,List<String>labels,List<String>defaults,Consumer<List<String>>handler){
        long gen=next(p);if(plugin.crossplay.input(p,title,labels,defaults,gen,handler))return;
        p.closeInventory();Prompt prompt=new Prompt(p,gen,title,List.copyOf(labels),List.copyOf(defaults),List.of(),0,System.currentTimeMillis()+120000,handler);prompts.put(p.getUniqueId(),prompt);ask(prompt);
    }
    private void ask(Prompt p){plugin.tell(p.player(),p.title()+" | "+p.labels().get(p.index())+" (default: "+p.defaults().get(p.index())+"). Type '.' to keep the default or 'cancel' to stop. Your answer is private.");}
    boolean awaiting(UUID id){return prompts.containsKey(id);}
    @EventHandler(priority=EventPriority.LOWEST)public void chat(AsyncPlayerChatEvent e){
        Prompt prompt=prompts.get(e.getPlayer().getUniqueId());if(prompt==null)return;e.setCancelled(true);String text=clean(e.getMessage(),1024);
        plugin.sync(()->{
            if(!prompts.remove(e.getPlayer().getUniqueId(),prompt)||System.currentTimeMillis()>prompt.expires())return;
            if(text.equalsIgnoreCase("cancel")){next(prompt.player());home(prompt.player());return;}
            if(!plugin.require(prompt.player(),"access")||generations.getOrDefault(prompt.player().getUniqueId(),0L)!=prompt.generation())return;
            List<String>answers=new ArrayList<>(prompt.answers());answers.add(text.equals(".")?prompt.defaults().get(prompt.index()):text);
            if(prompt.index()+1<prompt.labels().size()){
                Prompt next=new Prompt(prompt.player(),prompt.generation(),prompt.title(),prompt.labels(),prompt.defaults(),List.copyOf(answers),prompt.index()+1,System.currentTimeMillis()+120000,prompt.done());prompts.put(prompt.player().getUniqueId(),next);ask(next);
            }else callback(prompt.player(),prompt.generation(),()->prompt.done().accept(List.copyOf(answers)));
        });
    }
    void expirePrompts(){long now=System.currentTimeMillis();prompts.entrySet().removeIf(e->{if(e.getValue().expires()<now){plugin.tell(e.getValue().player(),"Input expired. Open /sal to start again.");return true;}return false;});imports.entrySet().removeIf(e->e.getValue().preview.created()<now-600000);}
    void forget(UUID id){generations.remove(id);prompts.remove(id);imports.remove(id);}
    void closeAll(){for(Player p:Bukkit.getOnlinePlayers())if(p.getOpenInventory().getTopInventory().getHolder() instanceof View)p.closeInventory();generations.replaceAll((id,n)->n+1);prompts.clear();imports.clear();}
    void home(Player p){
        List<Button>b=new ArrayList<>();
        if(plugin.allowed(p,"view")){
            b.add(button(p,"Online players",Bukkit.getOnlinePlayers().size()+" connected players",Material.PLAYER_HEAD,"view",()->players(p,true,"",0)));
            b.add(button(p,"Player database",plugin.engine.profiles().size()+" locally registered profiles, including offline players",Material.BOOK,"view",()->players(p,false,"",0)));
            b.add(button(p,"Alternative accounts","Review evidence, confirmed groups and excluded matches",Material.ENDER_EYE,"view",()->altOverview(p)));
        }
        if(plugin.allowed(p,"ban"))b.add(button(p,"Active bans & device blocks","Real account bans, ultra bans, exact IP bans and device blocks",Material.IRON_BARS,"ban",()->rules(p,null,true)));
        if(plugin.allowed(p,"permissions"))b.add(button(p,"Admin roles & individual rights","Local roles, grants, denials, durations and alternative-account inheritance",Material.SHIELD,"permissions",()->players(p,false,"",0)));
        if(plugin.allowed(p,"restrictions"))b.add(button(p,"Restriction templates","Configured restrictions; expiry and inherited restrictions",Material.WRITABLE_BOOK,"restrictions",()->templates(p,null)));
        if(plugin.allowed(p,"transfer"))b.add(button(p,"Import / export bans","Read-only preview, individual selection and Minecraft export",Material.HOPPER,"transfer",()->transfers(p)));
        if(plugin.allowed(p,"updates"))b.add(button(p,"Geyser / Floodgate updates",plugin.updates.status,Material.CLOCK,"updates",()->updateMenu(p)));
        if(plugin.allowed(p,"settings"))b.add(button(p,"Suite settings","Branding, integration settings, templates and reload",Material.COMPARATOR,"settings",()->settings(p)));
        if(plugin.allowed(p,"audit"))b.add(button(p,"Security audit","Recent administrative and enforcement events",Material.WRITTEN_BOOK,"audit",()->audit(p)));
        show(p,"Security dashboard","Simple Admin Suite Lite 1.0.0\nProfiles: "+plugin.engine.profiles().size()+" | Active bans: "+plugin.engine.rules().stream().filter(r->r.active(System.currentTimeMillis())&&Set.of(Kind.BAN,Kind.IP,Kind.DEVICE).contains(r.kind())).count()+"\nSentinel: "+(plugin.bridge==null?"Disabled or unavailable":plugin.bridge.status()),b,0,()->p.closeInventory());
    }
    private void players(Player p,boolean online,String query,int page){
        List<Button>b=new ArrayList<>();b.add(button(p,"Search players","Search a registered name or UUID; leave blank for all",Material.SPYGLASS,"view",()->input(p,"Find player",List.of("Name or UUID"),List.of(query),v->players(p,online,v.get(0),0))));
        String filter=query.toLowerCase(Locale.ROOT);
        plugin.engine.profiles().stream().filter(profile->!online||Bukkit.getPlayer(profile.id())!=null).filter(profile->profile.name().toLowerCase(Locale.ROOT).contains(filter)||profile.id().toString().contains(filter)).sorted(Comparator.comparing(Profile::last).reversed()).forEach(profile->b.add(button(p,profile.name(),profile.platform()+" | "+(Bukkit.getPlayer(profile.id())==null?"Offline":"Online")+"\nLast seen: "+when(profile.last())+"\n"+profile.id(),Material.PLAYER_HEAD,"view",()->profile(p,profile.id()))));
        show(p,online?"Online players":"Player database","Registered profiles are stored by UUID. Java client signals are not a hardware fingerprint.",b,page,()->home(p));
    }
    void profile(Player p,UUID id){
        if(!plugin.require(p,"view"))return;Profile target=plugin.engine.profile(id);String name=target==null?id.toString():target.name();List<Button>b=new ArrayList<>();
        if(target!=null){
            b.add(button(p,"Client & device insight",target.platform()+" | "+target.client().getOrDefault("brand",target.client().getOrDefault("deviceModel","Unknown")),Material.COMPARATOR,"devices",()->client(p,id)));
            b.add(button(p,"Device history",target.tokens("device").size()+" reported Bedrock devices",Material.RECOVERY_COMPASS,"devices",()->devices(p,id)));
            b.add(button(p,"IP & account history",target.tokens("ip").size()+" IP addresses | First seen "+when(target.first()),Material.MAP,"devices",()->history(p,id)));
            b.add(button(p,"Alternative accounts",Math.max(0,plugin.engine.component(id).size()-1)+" strongly linked accounts; IP hints are reviewed separately",Material.ENDER_EYE,"view",()->alts(p,id)));
        }
        if(plugin.allowed(p,"kick"))b.add(button(p,"Kick player","Immediately disconnect the current session",Material.OAK_DOOR,"kick",()->input(p,"Kick player",List.of("Reason"),List.of("Removed by server staff"),v->confirm(p,"Confirm kick",name+"\nReason: "+v.get(0),"kick",()->{Player live=Bukkit.getPlayer(id);if(live==null)throw new IllegalArgumentException("Player is offline");plugin.audit(p.getName(),"KICK",id+" / "+v.get(0));live.kickPlayer(v.get(0));profile(p,id);},()->profile(p,id)))));
        if(plugin.allowed(p,"ban"))b.add(button(p,"Ban / ultra ban","A real login ban; duration, reason and linked-account inheritance",Material.IRON_BARS,"ban",()->ban(p,id)));
        if(plugin.allowed(p,"permissions"))b.add(button(p,"Individual permissions","Grant or deny a permission with duration and inheritance",Material.NAME_TAG,"permissions",()->permission(p,id)));
        if(plugin.allowed(p,"roles"))b.add(button(p,"Admin role","Assign a local role; roles do not inherit to alt accounts",Material.SHIELD,"roles",()->roles(p,id)));
        if(plugin.allowed(p,"restrictions"))b.add(button(p,"Apply restriction template","Chat, commands, building, inventory, interaction or combat",Material.WRITABLE_BOOK,"restrictions",()->templates(p,id)));
        b.add(button(p,"Active rules & inherited rules","Review original sources and remove a selected rule",Material.CLOCK,"view",()->rules(p,id,false)));
        show(p,name,"UUID: "+id+"\n"+(target==null?"Imported or manually entered account":target.platform()+" | Last seen: "+when(target.last())),b,0,()->players(p,false,"",0));
    }
    private void detail(Player p,String title,String body,Runnable back){
        List<Button>buttons=new ArrayList<>();String[]lines=body.split("\n");for(int i=0;i<lines.length;i++){
            String line=lines[i];buttons.add(button(p,"Field "+(i+1),line,Material.PAPER,"",()->{plugin.tell(p,line);detail(p,title,body,back);}));
        }show(p,title,body,buttons,0,back);
    }
    private void client(Player p,UUID id){
        Profile target=plugin.engine.profile(id);if(target==null)return;StringBuilder body=new StringBuilder("Platform: "+target.platform()+"\n");
        target.client().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->body.append(e.getKey()).append(": ").append(e.getValue().isBlank()?"Unavailable":e.getValue()).append('\n'));
        body.append(target.platform().equals("JAVA")?"Java exposes no reliable hardware identifier. Brands and channels may be spoofed; this is not a complete mod list.":"Bedrock device IDs are client-reported and can change or be spoofed. XUID identifies an authenticated Xbox account; it is not a device ID.");
        detail(p,"Client insight",body.toString(),()->profile(p,id));
    }
    private void devices(Player p,UUID id){
        Profile target=plugin.engine.profile(id);if(target==null)return;List<Button>b=new ArrayList<>();
        for(Seen seen:target.history())if(seen.token().startsWith("device:"))b.add(button(p,seen.details().getOrDefault("deviceModel","Bedrock device"),"First: "+when(seen.first())+"\nLast: "+when(seen.last())+"\n"+seen.token(),Material.COMPARATOR,"devices",()->device(p,id,seen)));
        show(p,"Device history","Only available, observed identifiers are listed. Java sessions have no hardware device ID.",b,0,()->profile(p,id));
    }
    private void device(Player p,UUID id,Seen seen){
        List<Button>b=new ArrayList<>();b.add(button(p,"Inspect reported device",seen.details().toString(),Material.PAPER,"devices",()->detail(p,"Device metadata",seen.token()+"\nFirst: "+when(seen.first())+"\nLast: "+when(seen.last())+"\n"+seen.details(),()->device(p,id,seen))));
        if(plugin.allowed(p,"ban"))b.add(button(p,"Block this device","All observed accounts using this reported identifier; no model-wide ban",Material.IRON_BARS,"ban",()->input(p,"Device block",List.of("Duration","Reason"),List.of("permanent","Device blocked by server staff"),v->{Rule r=Rule.make(Kind.DEVICE,id,seen.token(),"manual",expiry(v.get(0),System.currentTimeMillis()),false,v.get(1),p.getName());confirmRule(p,r,"ban",()->device(p,id,seen));})));
        show(p,"Bedrock device",seen.token()+"\n"+seen.details(),b,0,()->devices(p,id));
    }
    private void history(Player p,UUID id){
        Profile target=plugin.engine.profile(id);if(target==null)return;List<Button>b=new ArrayList<>();
        for(Seen seen:target.history())if(seen.token().startsWith("ip:")){
            String ip=seen.token().substring(3);b.add(button(p,ip,"First: "+when(seen.first())+"\nLast: "+when(seen.last())+"\nShared IPs alone do not inherit account restrictions.",Material.MAP,"devices",()->{
                List<Button>actions=new ArrayList<>();if(plugin.allowed(p,"ban"))actions.add(button(p,"Create exact IP ban","Affects every user of this address, including shared networks. Bedrock device bindings inherit this ban's expiry.",Material.IRON_BARS,"ban",()->input(p,"IP ban",List.of("Duration","Reason"),List.of("1d","Network access denied by staff"),v->confirmRule(p,Rule.make(Kind.IP,null,ip,"manual",expiry(v.get(0),System.currentTimeMillis()),false,v.get(1),p.getName()),"ban",()->history(p,id)))));
                show(p,"IP observation",ip+"\nFirst: "+when(seen.first())+"\nLast: "+when(seen.last()),actions,0,()->history(p,id));
            }));
        }show(p,"IP history","Saved observations for "+target.name(),b,0,()->profile(p,id));
    }
    private void altOverview(Player p){
        List<Button>b=new ArrayList<>();for(Profile target:plugin.engine.profiles()){
            List<Match>matches=plugin.engine.matches(target.id());if(!matches.isEmpty())b.add(button(p,target.name(),matches.size()+" accounts with evidence; "+Math.max(0,plugin.engine.component(target.id()).size()-1)+" strong links",Material.PLAYER_HEAD,"view",()->alts(p,target.id())));
        }show(p,"Alternative accounts","Reported devices, authenticated Xbox accounts and staff links form strong groups. IP and self-signed ID matches remain unconfirmed hints.",b,0,()->home(p));
    }
    private void alts(Player p,UUID id){
        List<Button>b=new ArrayList<>();b.add(button(p,"Link a registered account","Confirm or exclude an account link by name or UUID",Material.NAME_TAG,"links",()->input(p,"Confirm account link",List.of("Other player name or UUID"),List.of(""),v->{UUID other=plugin.engine.resolve(v.get(0));if(other==null)throw new IllegalArgumentException("No registered profile found");linkDetail(p,id,other);} )));
        for(Match m:plugin.engine.matches(id))b.add(button(p,m.name(),(m.strong()?"Strong group":"Unconfirmed hint")+"\n"+String.join("\n",m.evidence())+"\nLink setting: "+plugin.engine.linkType(id,m.id()),Material.PLAYER_HEAD,"view",()->linkDetail(p,id,m.id())));
        show(p,"Account evidence","Review the evidence before confirming an account link. Confirmed links can inherit active bans and denied rights.",b,0,()->profile(p,id));
    }
    private void linkDetail(Player p,UUID id,UUID other){
        Profile target=plugin.engine.profile(other);if(target==null||id.equals(other))throw new IllegalArgumentException("Choose a different registered account");List<Button>b=new ArrayList<>();
        b.add(button(p,"Open account profile",target.name(),Material.PLAYER_HEAD,"view",()->profile(p,other)));
        for(String type:List.of("confirmed","ignored","automatic"))b.add(button(p,"Set link: "+type,"Changes automatic punishment and denied-permission inheritance",Material.NAME_TAG,"links",()->confirm(p,"Change account link",id+"\n"+other+"\nNew setting: "+type,"links",()->plugin.task(p,()->{plugin.engine.link(id,other,type,p.getName());return true;},v->{plugin.enforce();alts(p,id);}),()->alts(p,id))));
        show(p,"Account link",target.name()+"\n"+other+"\nSetting: "+plugin.engine.linkType(id,other),b,0,()->alts(p,id));
    }
    private static boolean bool(String value){if(!Set.of("true","false").contains(value.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Use true or false");return Boolean.parseBoolean(value);}
    private void ban(Player p,UUID id){
        input(p,"Create real ban",List.of("Mode: account or ultra","Duration","Inherit to strongly linked accounts: true/false","Reason"),List.of("ultra","permanent","true","Banned by server staff"),v->{
            if(!Set.of("account","ultra").contains(v.get(0)))throw new IllegalArgumentException("Mode must be account or ultra");
            Rule r=Rule.make(Kind.BAN,id,"",v.get(0),expiry(v.get(1),System.currentTimeMillis()),bool(v.get(2)),v.get(3),p.getName());confirmRule(p,r,"ban",()->profile(p,id));
        });
    }
    private void permission(Player p,UUID id){
        input(p,"Individual permission",List.of("Permission node","Value: allow or deny","Duration","Inherit to strongly linked accounts: true/false","Reason"),List.of("essentials.fly","deny","1d","true","Restricted by server staff"),v->{
            if(!Set.of("allow","deny").contains(v.get(1)))throw new IllegalArgumentException("Value must be allow or deny");
            Rule r=Rule.make(Kind.PERMISSION,id,node(v.get(0)),""+v.get(1).equals("allow"),expiry(v.get(2),System.currentTimeMillis()),bool(v.get(3)),v.get(4),p.getName());confirmRule(p,r,"permissions",()->profile(p,id));
        });
    }
    private void roles(Player p,UUID id){
        List<Button>b=new ArrayList<>();var roles=plugin.getConfig().getConfigurationSection("roles");if(roles!=null)for(String role:roles.getKeys(false))b.add(button(p,role,String.join(", ",plugin.getConfig().getStringList("roles."+role)),Material.SHIELD,"roles",()->input(p,"Assign role: "+role,List.of("Duration","Reason"),List.of("permanent","Role assigned by staff"),v->confirmRule(p,Rule.make(Kind.ROLE,id,role,"",expiry(v.get(0),System.currentTimeMillis()),false,v.get(1),p.getName()),"roles",()->profile(p,id)))));
        b.add(button(p,"Review / remove existing roles","All local rules for this account",Material.CLOCK,"roles",()->rules(p,id,false)));
        show(p,"Admin roles","Roles are local to Lite, work alongside LuckPerms and never inherit to alternative accounts. Editing roles requires simpleadminlite.roles.",b,0,()->profile(p,id));
    }
    private void templates(Player p,UUID id){
        List<Button>b=new ArrayList<>();var presets=plugin.getConfig().getConfigurationSection("restriction-templates");
        if(presets!=null)for(String preset:presets.getKeys(false)){
            String flags=String.join(",",plugin.getConfig().getStringList("restriction-templates."+preset));
            b.add(button(p,preset,"Restricted actions: "+flags,Material.WRITABLE_BOOK,"restrictions",()->{
                if(id==null){players(p,false,"",0);return;}
                input(p,"Apply "+preset,List.of("Duration","Inherit to strongly linked accounts: true/false","Reason"),List.of("1h","true","Restricted by staff"),v->confirmRule(p,Rule.make(Kind.TEMPLATE,id,preset,flags,expiry(v.get(0),System.currentTimeMillis()),bool(v.get(1)),v.get(2),p.getName()),"restrictions",()->profile(p,id)));
            }));
        }show(p,"Restriction templates",id==null?"Choose a player from the database to apply a template.":"Template rules expire automatically. Inherited rules retain their original source.",b,0,id==null?()->home(p):()->profile(p,id));
    }
    private String summary(Rule r){return "Type: "+r.kind()+" / "+r.value()+"\nTarget: "+Objects.toString(r.target(),r.selector())+"\nSelector: "+r.selector()+"\nExpires: "+when(r.until())+"\nInherit alts: "+r.inherit()+"\nReason: "+r.reason()+"\nActor: "+r.actor()+"\nReference: "+r.id()+(r.parent().isBlank()?"":"\nParent: "+r.parent());}
    private void confirmRule(Player p,Rule r,String permission,Runnable back){
        if(r.reason().isBlank())throw new IllegalArgumentException("A reason is required");
        String body=summary(r);if(r.target()!=null)body+="\nKnown strong account group: "+plugin.engine.component(r.target()).size()+" account(s)";
        confirm(p,"Confirm security change",body,permission,()->{
            if(!r.active(System.currentTimeMillis()))throw new IllegalArgumentException("This rule has already expired; create a new rule");
            plugin.task(p,()->{plugin.engine.add(r);return r;},saved->{plugin.enforce();plugin.tell(p,"Saved: "+saved.id());back.run();});
        },back);
    }
    private void confirm(Player p,String title,String body,String permission,Runnable yes,Runnable no){
        show(p,title,body,List.of(button(p,"Confirm",body,Material.LIME_DYE,permission,yes),button(p,"Cancel","Make no change",Material.RED_DYE,"",no)),0,no);
    }
    private void rules(Player p,UUID id,boolean bansOnly){
        List<Rule>rows=id==null?plugin.engine.rules():plugin.engine.effective(id,System.currentTimeMillis());List<Button>b=new ArrayList<>();
        for(Rule r:rows)if(r.active(System.currentTimeMillis())&&(!bansOnly||Set.of(Kind.BAN,Kind.IP,Kind.DEVICE).contains(r.kind()))){
            String permission=switch(r.kind()){case BAN,IP,DEVICE->"ban";case PERMISSION->"permissions";case TEMPLATE->"restrictions";case ROLE->"roles";};
            b.add(button(p,r.kind()+" | "+r.id().substring(0,8),summary(r),Material.CLOCK,"view",()->{
                List<Button>actions=List.of(button(p,"Revoke original rule","This also removes inherited effects and IP-derived device bindings.",Material.RED_DYE,permission,()->confirm(p,"Revoke rule",summary(r),permission,()->plugin.task(p,()->{plugin.engine.revoke(r.id(),p.getName());return true;},v->{plugin.enforce();rules(p,id,bansOnly);}),()->rules(p,id,bansOnly))));
                show(p,"Rule details",summary(r),actions,0,()->rules(p,id,bansOnly));
            }));
        }show(p,"Active security rules","Inherited rules are displayed at their original source. Deny takes priority over local allow rules. LuckPerms is retained.",b,0,id==null?()->home(p):()->profile(p,id));
    }
    private void transfers(Player p){
        List<Button>b=new ArrayList<>();
        b.add(button(p,"Inspect Minecraft ban lists","Read banned-players.json and banned-ips.json. Nothing is imported yet.",Material.BOOK,"transfer",()->{
            plugin.tell(p,"Reading the existing ban lists for preview...");plugin.task(p,()->BanTransfer.vanilla(Path.of(".").toAbsolutePath(),p.getName()),preview->{imports.put(p.getUniqueId(),new Selection(preview));importMenu(p);});
        }));
        b.add(button(p,"Inspect a LiteBans database","Read a closed H2 or SQLite snapshot from the configured LiteBans folder; no source data is modified.",Material.HOPPER,"transfer",()->{
            Path path=Path.of(plugin.getConfig().getString("imports.litebans-file","plugins/LiteBans/litebans.mv.db")).toAbsolutePath().normalize();String prefix=plugin.getConfig().getString("imports.litebans-table-prefix","litebans_");
            plugin.tell(p,"Reading the LiteBans snapshot for preview...");plugin.task(p,()->BanTransfer.liteBans(path,prefix,p.getName()),preview->{imports.put(p.getUniqueId(),new Selection(preview));importMenu(p);});
        }));
        if(imports.containsKey(p.getUniqueId()))b.add(button(p,"Continue import preview","Your current selections expire after ten minutes",Material.MAP,"transfer",()->importMenu(p)));
        b.add(button(p,"Preview Minecraft export","Export known active account bans, known linked accounts and exact IP bans using Bukkit's ban lists",Material.CHEST,"transfer",()->exportMenu(p)));
        show(p,"Ban import / export","Imports are optional. Select entries individually or select all after inspection. Existing source bans remain under the source plugin's control.",b,0,()->home(p));
    }
    private void importMenu(Player p){
        Selection selection=imports.get(p.getUniqueId());if(selection==null||selection.preview.created()<System.currentTimeMillis()-600000)throw new IllegalArgumentException("Preview expired; inspect the source again");
        List<Button>b=new ArrayList<>();
        b.add(button(p,"Select all eligible rows",selection.preview.rows().size()+" active supported bans",Material.LIME_DYE,"transfer",()->{selection.preview.rows().forEach(row->selection.selected.add(row.key()));importMenu(p);}));
        b.add(button(p,"Clear selection","No entries will be imported",Material.RED_DYE,"transfer",()->{selection.selected.clear();importMenu(p);}));
        b.add(button(p,"Import selected ("+selection.selected.size()+")","Review and confirm the selected rows",Material.HOPPER,"transfer",()->{
            List<ImportRow> chosen=selection.preview.rows().stream().filter(row->selection.selected.contains(row.key())).toList();if(chosen.isEmpty())throw new IllegalArgumentException("Select at least one ban first");
            confirm(p,"Confirm selected import","Source: "+selection.preview.source()+"\nSelected: "+chosen.size()+"\nExisting imported records are skipped. Expiry and reasons are preserved.\nExact IP bans bind observed Bedrock devices; account-only bans do not create IP bans.","transfer",()->{
                if(selection.preview.created()<System.currentTimeMillis()-600000)throw new IllegalArgumentException("Preview expired");
                plugin.task(p,()->{
                    int count=plugin.engine.importRows(chosen,p.getName());
                    for(ImportRow row:chosen)if(row.rule().target()!=null&&plugin.engine.profile(row.rule().target())==null){
                        String label=row.label().startsWith("LiteBans")?row.rule().target().toString():row.label();
                        plugin.engine.observe(row.rule().target(),label,"UNKNOWN","",Map.of("source","Imported ban"),List.of(),System.currentTimeMillis(),64);
                    }return count;
                },count->{imports.remove(p.getUniqueId());plugin.enforce();plugin.tell(p,count+" new bans imported. Duplicate or expired rows were skipped.");transfers(p);});
            },()->importMenu(p));
        }));
        if(!selection.preview.skipped().isEmpty())b.add(button(p,"Skipped / unsupported rows",selection.preview.skipped().size()+" rows; inspect why they cannot be imported",Material.PAPER,"transfer",()->detail(p,"Import exclusions",String.join("\n",selection.preview.skipped().stream().limit(500).toList()),()->importMenu(p))));
        for(ImportRow row:selection.preview.rows()){
            boolean selected=selection.selected.contains(row.key());b.add(button(p,(selected?"[Selected] ":"[ ] ")+row.label(),summary(row.rule())+"\n"+row.warning(),selected?Material.LIME_DYE:Material.PAPER,"transfer",()->{
                show(p,"Inspect import row",summary(row.rule())+"\n"+row.warning(),List.of(button(p,selected?"Deselect":"Select this row",row.label(),selected?Material.RED_DYE:Material.LIME_DYE,"transfer",()->{if(selected)selection.selected.remove(row.key());else selection.selected.add(row.key());importMenu(p);})),0,()->importMenu(p));
            }));
        }show(p,"Import preview",selection.preview.source()+"\nEligible: "+selection.preview.rows().size()+" | Selected: "+selection.selected.size()+" | Skipped: "+selection.preview.skipped().size()+"\nNothing is imported until you confirm. Preview expires after ten minutes.",b,0,()->transfers(p));
    }
    private void exportMenu(Player p){
        VanillaExport.Plan plan=VanillaExport.preview(plugin.engine);List<Button>b=new ArrayList<>();
        b.add(button(p,"Export these bans to Minecraft",plan.entries().size()+" account/IP entries; stronger existing bans are preserved",Material.CHEST,"transfer",()->confirm(p,"Confirm Minecraft export","Entries: "+plan.entries().size()+"\nOnly known accounts and exact IPs can be exported. Device fingerprints, permission restrictions and unknown future alt accounts cannot be represented by vanilla. Existing longer bans remain.\nKeep Lite installed until the export is confirmed complete.","transfer",()->{
            int count=VanillaExport.apply(plan,plugin.engine,p.getName());plugin.audit(p.getName(),"VANILLA_EXPORT",count+" bans exported");plugin.tell(p,count+" entries added or extended in Minecraft's ban lists. Existing stronger bans were retained.");transfers(p);
        },()->exportMenu(p))));
        for(VanillaExport.Entry row:plan.entries())b.add(button(p,row.id()==null?row.ip():row.name().isBlank()?row.id().toString():row.name(),summary(row.rule()),Material.PAPER,"transfer",()->detail(p,"Export entry",summary(row.rule()),()->exportMenu(p))));
        show(p,"Minecraft export preview","Known accounts and current exact IP bans. Device-only bans become account bans for known observed users of the blocked device. A data change requires a fresh preview.",b,0,()->transfers(p));
    }
    private void updateMenu(Player p){
        show(p,"Crossplay updates",plugin.updates.status+"\nAutomatic hourly checks: "+plugin.getConfig().getBoolean("updates.hourly-check",true)+"\nOnly installed local Geyser/Floodgate JARs are updated. Official hashes, plugin identity, Java and API compatibility are checked. Updates wait in Paper's update folder for the next server restart.",List.of(
            button(p,"Check and stage now","Download compatible official builds when a newer file is available",Material.CLOCK,"updates",()->{plugin.updates.check();updateMenu(p);}),
            button(p,"Refresh status",plugin.updates.status,Material.COMPASS,"updates",()->updateMenu(p))),0,()->home(p));
    }
    private void settings(Player p){
        List<Button>b=new ArrayList<>();
        b.add(button(p,"Branding prefix",plugin.getConfig().getString("prefix",""),Material.NAME_TAG,"settings",()->input(p,"Chat prefix",List.of("Prefix (Minecraft & colour codes are supported)"),List.of(plugin.getConfig().getString("prefix","&8[&bSimple&8] &f")),v->{if(v.get(0).length()>100)throw new IllegalArgumentException("Prefix limit: 100 characters");saveSetting(p,"prefix",v.get(0));})));
        for(String path:List.of("updates.hourly-check","bridge.enabled","client-checks.enabled","client-checks.kick-on-match","discord-webhook.enabled"))b.add(button(p,path,"Current: "+plugin.getConfig().getBoolean(path),Material.LEVER,"settings",()->confirm(p,"Change setting",path+" = "+!plugin.getConfig().getBoolean(path),"settings",()->saveSetting(p,path,!plugin.getConfig().getBoolean(path)),()->settings(p))));
        b.add(button(p,"Sentinel receiver port","Current: "+plugin.getConfig().getInt("bridge.port",39093)+" | Loopback only",Material.REPEATER,"settings",()->input(p,"Receiver port",List.of("TCP port"),List.of(""+plugin.getConfig().getInt("bridge.port",39093)),v->{int port=Integer.parseInt(v.get(0));if(port<1024||port>65535)throw new IllegalArgumentException("Use a port from 1024 to 65535");saveSetting(p,"bridge.port",port);})));
        b.add(button(p,"Optional Discord webhook","Only blocked-login / possible ban-evasion notifications; no bot or app link. URL is never shown in menu details.",Material.PAPER,"settings",()->input(p,"Discord webhook",List.of("HTTPS Discord webhook URL (leave blank to remove)"),List.of(""),v->{String url=v.get(0);if(!url.isBlank()&&!url.matches("https://(?:(?:canary|ptb)\\.)?discord(?:app)?\\.com/api/webhooks/[0-9]+/[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Use an official Discord HTTPS webhook URL");saveSetting(p,"discord-webhook.url",url);})));
        b.add(button(p,"Edit admin role definition","Define a role name and comma-separated permission nodes",Material.SHIELD,"settings",()->input(p,"Role definition",List.of("Role ID (letters, numbers, dash)","Permission nodes, comma separated"),List.of("viewer","simpleadminlite.access,simpleadminlite.view,simpleadminlite.devices"),v->{String role=v.get(0).toLowerCase(Locale.ROOT);if(!role.matches("[a-z0-9_-]{1,40}"))throw new IllegalArgumentException("Invalid role ID");List<String>nodes=Arrays.stream(v.get(1).split(",")).map(Model::node).toList();saveSetting(p,"roles."+role,nodes);})));
        b.add(button(p,"Edit restriction template","Allowed flags: chat,commands,building,interactions,inventory,combat",Material.WRITABLE_BOOK,"settings",()->input(p,"Restriction template",List.of("Template ID","Restricted actions, comma separated"),List.of("building","building"),v->{String id=v.get(0).toLowerCase(Locale.ROOT);if(!id.matches("[a-z0-9_-]{1,40}"))throw new IllegalArgumentException("Invalid template ID");List<String>flags=Arrays.stream(v.get(1).split(",")).map(String::strip).toList();if(!Set.of("chat","commands","building","interactions","inventory","combat").containsAll(flags))throw new IllegalArgumentException("Unknown restriction flag");saveSetting(p,"restriction-templates."+id,flags);})));
        b.add(button(p,"Client access rules","Optional exact reported brand/channel matches. These are unverified signals, not proof of cheating.",Material.SPYGLASS,"settings",()->input(p,"Java client access rules",List.of("Denied brands, comma separated (blank = none)","Denied channels, comma separated (blank = none)"),List.of(String.join(",",plugin.getConfig().getStringList("client-checks.denied-brands")),String.join(",",plugin.getConfig().getStringList("client-checks.denied-channels"))),v->{plugin.getConfig().set("client-checks.denied-brands",csv(v.get(0)));plugin.getConfig().set("client-checks.denied-channels",csv(v.get(1)));saveSetting(p,"client-checks.enabled",plugin.getConfig().getBoolean("client-checks.enabled"));})));
        b.add(button(p,"LiteBans snapshot settings","No automatic migration; this path is read only when you request a preview",Material.HOPPER,"settings",()->input(p,"LiteBans source",List.of("Local database snapshot file","Table prefix"),List.of(plugin.getConfig().getString("imports.litebans-file","plugins/LiteBans/litebans.mv.db"),plugin.getConfig().getString("imports.litebans-table-prefix","litebans_")),v->{if(!v.get(1).matches("[A-Za-z0-9_]{0,50}"))throw new IllegalArgumentException("Invalid table prefix");plugin.getConfig().set("imports.litebans-table-prefix",v.get(1));saveSetting(p,"imports.litebans-file",v.get(0));})));
        b.add(button(p,"Reload suite configuration","Reload settings without restarting the server",Material.COMPASS,"settings",()->{plugin.onCommand(p,plugin.getCommand("sal"),"sal",new String[]{"reload"});home(p);}));
        show(p,"Suite settings","Prefix, menus and stored data belong to this independent Lite installation. Advanced settings are documented in config.yml. Bridge port changes require copying the updated bridge-link.properties and restarting Geyser.",b,0,()->home(p));
    }
    private static List<String> csv(String text){return Arrays.stream(text.split(",")).map(String::strip).filter(s->!s.isBlank()).distinct().limit(128).toList();}
    private void saveSetting(Player p,String path,Object value){
        if(!plugin.require(p,"settings"))return;plugin.getConfig().set(path,value);plugin.saveConfig();plugin.audit(p.getName(),"SETTING",path);plugin.onCommand(p,plugin.getCommand("sal"),"sal",new String[]{"reload"});settings(p);
    }
    private void audit(Player p){plugin.task(p,()->plugin.store.audit(100),lines->detail(p,"Security audit",String.join("\n",lines),()->home(p)));}
}
