package io.github.robin9094707.simpleadminlite;

import org.bukkit.*;
import org.bukkit.ban.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import static io.github.robin9094707.simpleadminlite.Model.*;

public final class SimpleAdminLite extends JavaPlugin implements Listener,CommandExecutor,TabCompleter {
    Store store;Engine engine;Menus menus;Crossplay crossplay;BridgeServer bridge;Updates updates;
    private ExecutorService io,network;
    private final Map<UUID,PermissionAttachment> attachments=new HashMap<>();
    private final ConcurrentMap<UUID,Set<String>> currentTokens=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,Long> webhookLimits=new ConcurrentHashMap<>();
    private volatile String prefix="§8[§bSimple§8] §f",identitySecret="",webhook="";
    private boolean autoUpdates;private int refreshTick;
    private final HttpClient webHttp=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Override public void onEnable(){
        try{
            saveDefaultConfig();loadSettings();
            io=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4096),threadFactory("SimpleLite-Storage"),new ThreadPoolExecutor.AbortPolicy());
            network=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),threadFactory("SimpleLite-Network"),new ThreadPoolExecutor.AbortPolicy());
            store=new Store(getDataFolder().toPath().resolve("security.sqlite"));engine=new Engine(store);
            crossplay=new Crossplay(this);menus=new Menus(this);updates=new Updates(this);startBridge();
            Bukkit.getPluginManager().registerEvents(this,this);Bukkit.getPluginManager().registerEvents(menus,this);
            Objects.requireNonNull(getCommand("sal")).setExecutor(this);getCommand("sal").setTabCompleter(this);
            Objects.requireNonNull(getCommand("salrestrictions")).setExecutor(this);
            Bukkit.getMessenger().registerIncomingPluginChannel(this,"minecraft:brand",(channel,player,data)->Bukkit.getScheduler().runTaskLater(this,()->capture(player),1));
            Bukkit.getScheduler().runTaskTimer(this,()->{
                refreshPermissions();menus.expirePrompts();
                if(++refreshTick%15==0)for(Player p:Bukkit.getOnlinePlayers())capture(p);
            },20,40);
            Bukkit.getScheduler().runTaskTimer(this,()->{if(autoUpdates)updates.check();},100,72000);
            for(Player p:Bukkit.getOnlinePlayers())capture(p);
            getLogger().info("Simple Admin Suite Lite 1.0.0 enabled. English only. Profiles: "+engine.profiles().size());
        }catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"Cannot initialize Lite; existing data was retained",e);Bukkit.getPluginManager().disablePlugin(this);}
    }
    private static ThreadFactory threadFactory(String name){return r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;};}
    private void loadSettings(){
        if(getConfig().getString("identity-secret","").isBlank()){getConfig().set("identity-secret",Crypto.secret());saveConfig();}
        if(getConfig().getString("bridge.shared-secret","").isBlank()){getConfig().set("bridge.shared-secret",Crypto.secret());saveConfig();}
        identitySecret=getConfig().getString("identity-secret");prefix=ChatColor.translateAlternateColorCodes('&',getConfig().getString("prefix","&8[&bSimple&8] &f"));
        autoUpdates=getConfig().getBoolean("updates.hourly-check",true);webhook=getConfig().getString("discord-webhook.url","");
        if(!getConfig().getBoolean("discord-webhook.enabled",false))webhook="";
    }
    private void startBridge(){
        if(bridge!=null)bridge.close();bridge=null;
        if(!getConfig().getBoolean("bridge.enabled",true))return;
        try{
            Properties p=new Properties();p.setProperty("host",getConfig().getString("bridge.host","127.0.0.1"));p.setProperty("port",""+getConfig().getInt("bridge.port",39093));p.setProperty("shared-secret",getConfig().getString("bridge.shared-secret"));
            Path link=getDataFolder().toPath().resolve("bridge-link.properties");Files.write(link,Model.encode(p));
            try{Files.setPosixFilePermissions(link,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            bridge=new BridgeServer(this,p.getProperty("shared-secret"));bridge.start();
        }catch(Exception e){if(bridge!=null)bridge.close();bridge=null;getLogger().warning("Optional Sentinel receiver unavailable: "+e.getMessage());}
    }
    @Override public void onDisable(){
        if(bridge!=null)bridge.close();
        for(var e:attachments.entrySet()){Player p=Bukkit.getPlayer(e.getKey());if(p!=null)p.removeAttachment(e.getValue());}attachments.clear();
        if(menus!=null)menus.closeAll();
        if(network!=null)network.shutdownNow();
        if(io!=null){io.shutdown();try{if(!io.awaitTermination(10,TimeUnit.SECONDS))getLogger().severe("Storage work is still pending during shutdown.");}catch(InterruptedException e){Thread.currentThread().interrupt();}}
        if(store!=null)try{store.close();}catch(Exception e){getLogger().warning("Database close failed: "+e.getMessage());}
    }
    boolean allowed(CommandSender sender,String action){return sender.isOp()||sender.hasPermission("simpleadminlite.admin")||sender.hasPermission("simpleadminlite."+action);}
    boolean require(CommandSender sender,String action){if(allowed(sender,action))return true;tell(sender,"You do not have permission: simpleadminlite."+action);return false;}
    void tell(CommandSender sender,String text){sender.sendMessage(prefix+text);}
    void sync(Runnable work){if(!isEnabled())return;if(Bukkit.isPrimaryThread())work.run();else try{Bukkit.getScheduler().runTask(this,work);}catch(IllegalStateException ignored){}}
    void network(Runnable work){try{network.execute(work);}catch(RejectedExecutionException e){getLogger().warning("Network queue is full; task was not started.");}}
    interface Job<T>{T run() throws Exception;}
    <T> void task(CommandSender actor,Job<T> job,Consumer<T> done){
        try{io.execute(()->{try{T value=job.run();sync(()->{if(!(actor instanceof Player p)||p.isOnline())done.accept(value);});}catch(Exception error){getLogger().log(java.util.logging.Level.WARNING,"Lite operation failed",error);sync(()->tell(actor,"Operation failed: "+clean(error.getMessage(),200)));}});}
        catch(RejectedExecutionException e){tell(actor,"Storage queue is busy. Try again shortly; no change was made.");}
    }
    void audit(String actor,String action,String detail){try{io.execute(()->{try{store.audit(actor,action,detail);}catch(Exception e){getLogger().warning("Audit write failed");}});}catch(RejectedExecutionException e){getLogger().warning("Audit queue is full");}}
    @EventHandler(priority=EventPriority.HIGHEST) public void preLogin(AsyncPlayerPreLoginEvent event){
        if(event.getLoginResult()!=AsyncPlayerPreLoginEvent.Result.ALLOWED||engine==null)return;
        UUID id=event.getUniqueId();String ip=event.getAddress().getHostAddress();Map<String,String> metadata=new LinkedHashMap<>();Set<String>tokens=new LinkedHashSet<>();
        String xuid=crossplay.xuid(id);if(!xuid.isBlank())tokens.add(Crypto.token("xuid",xuid,identitySecret));
        BridgeServer.Snapshot snapshot=bridge==null?null:bridge.get(xuid);
        if(snapshot!=null&&snapshotMatches(id,snapshot.data()))extract(snapshot.data(),metadata,tokens);
        Rule denied=engine.denial(id,ip,tokens,System.currentTimeMillis());
        if(denied!=null){
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,banMessage(denied));
            evade(id,event.getName(),denied);
            Set<String> captured=Set.copyOf(tokens);try{io.execute(()->{try{engine.observe(id,event.getName(),xuid.isBlank()?"JAVA":"BEDROCK",ip,metadata,captured,System.currentTimeMillis(),64);for(String token:captured)engine.learnDeviceBan(denied,id,token,System.currentTimeMillis());}catch(Exception e){getLogger().warning("Denied login observation could not be stored");}});}catch(RejectedExecutionException ignored){}
        }
    }
    @EventHandler public void join(PlayerJoinEvent event){
        Player p=event.getPlayer();currentTokens.remove(p.getUniqueId());capture(p);
        Bukkit.getScheduler().runTaskLater(this,()->{if(p.isOnline()){capture(p);if(allowed(p,"updates")&&!updates.ready.isEmpty())tell(p,"Geyser/Floodgate updates are staged for the next server restart: "+String.join(", ",updates.ready));}},60);
    }
    @EventHandler public void quit(PlayerQuitEvent event){UUID id=event.getPlayer().getUniqueId();currentTokens.remove(id);attachments.remove(id);menus.forget(id);String xuid=crossplay.xuid(id);if(bridge!=null)bridge.forget(xuid);}
    @EventHandler public void channel(PlayerRegisterChannelEvent event){Bukkit.getScheduler().runTaskLater(this,()->capture(event.getPlayer()),1);}
    @EventHandler public void channel(PlayerUnregisterChannelEvent event){Bukkit.getScheduler().runTaskLater(this,()->capture(event.getPlayer()),1);}
    @EventHandler public void locale(PlayerLocaleChangeEvent event){Bukkit.getScheduler().runTaskLater(this,()->capture(event.getPlayer()),1);}
    void onBridgeSnapshot(String xuid){sync(()->{for(Player p:Bukkit.getOnlinePlayers())if(xuid.equals(crossplay.xuid(p.getUniqueId())))capture(p);});}
    private boolean snapshotMatches(UUID id,Properties p){String uuid=p.getProperty("javaUuid","");return uuid.isBlank()||uuid.equalsIgnoreCase(id.toString());}
    private void extract(Properties p,Map<String,String>data,Set<String>tokens){
        for(String key:List.of("deviceModel","deviceOs","gameVersion","languageCode","currentInputMode","defaultInputMode","uiProfile","collectionSource"))if(p.containsKey(key))data.put(key,clean(p.getProperty(key),256));
        for(String[]type:new String[][]{{"device","deviceId"},{"self","selfSignedId"}}){String raw=p.getProperty(type[1],"");if(!raw.isBlank()&&!raw.equals("0")&&!raw.equals("00000000-0000-0000-0000-000000000000"))tokens.add(Crypto.token(type[0],raw,identitySecret));}
        data.put("bridgeObservedAt",""+System.currentTimeMillis());
    }
    void capture(Player player){
        if(!player.isOnline())return;UUID id=player.getUniqueId();String name=player.getName(),ip=player.getAddress()==null?"":player.getAddress().getAddress().getHostAddress();
        boolean bedrock=crossplay.bedrock(id);Map<String,String>metadata=new LinkedHashMap<>();Set<String>tokens=new LinkedHashSet<>();
        if(bedrock){
            metadata.putAll(crossplay.metadata(id));String xuid=crossplay.xuid(id);if(!xuid.isBlank())tokens.add(Crypto.token("xuid",xuid,identitySecret));
            BridgeServer.Snapshot s=bridge==null?null:bridge.get(xuid);if(s!=null&&snapshotMatches(id,s.data()))extract(s.data(),metadata,tokens);
        }else{
            metadata.put("brand",Crossplay.text(player,"getClientBrandName"));metadata.put("protocol",Crossplay.text(player,"getProtocolVersion"));
            metadata.put("locale",player.getLocale());metadata.put("ping",""+player.getPing());metadata.put("viewDistance",""+player.getClientViewDistance());
            metadata.put("channels",String.join(", ",player.getListeningPluginChannels().stream().sorted().limit(128).map(s->clean(s,128)).toList()));
            metadata.put("collectionSource","Server-visible Java client signals (unverified)");
        }
        currentTokens.put(id,Set.copyOf(tokens));int historyLimit=Math.max(8,Math.min(512,getConfig().getInt("history.per-type-limit",64)));
        task(Bukkit.getConsoleSender(),()->{
            Profile profile=engine.observe(id,name,bedrock?"BEDROCK":"JAVA",ip,metadata,tokens,System.currentTimeMillis(),historyLimit);
            Rule r=engine.denial(id,ip,tokens,System.currentTimeMillis());if(r!=null)for(String token:tokens)engine.learnDeviceBan(r,id,token,System.currentTimeMillis());return profile;
        },profile->{if(Bukkit.getPlayer(id)==player){enforce(true);clientCheck(player,profile);}});
    }
    private void clientCheck(Player p,Profile profile){
        if(!getConfig().getBoolean("client-checks.enabled",false)||profile.platform().equals("BEDROCK"))return;
        String brand=profile.client().getOrDefault("brand","");List<String> denied=getConfig().getStringList("client-checks.denied-brands");
        List<String> channels=getConfig().getStringList("client-checks.denied-channels");String observed=profile.client().getOrDefault("channels","");
        boolean match=denied.stream().anyMatch(s->!s.isBlank()&&s.equalsIgnoreCase(brand))||channels.stream().anyMatch(s->!s.isBlank()&&Arrays.asList(observed.split(", ")).contains(s));
        if(match){audit("Client rules","UNVERIFIED_CLIENT_SIGNAL",p.getUniqueId()+" / "+brand);if(getConfig().getBoolean("client-checks.kick-on-match",false))p.kickPlayer("Your reported client signals match a configured server access rule. Please contact staff.");}
    }
    String banMessage(Rule r){return ChatColor.stripColor(prefix)+"Access denied\nReason: "+r.reason()+"\nExpires: "+when(r.until())+"\nReference: "+r.id().substring(0,8);}
    void enforce(){enforce(false);}
    private void enforce(boolean possibleEvasion){
        for(Player p:Bukkit.getOnlinePlayers()){
            String ip=p.getAddress()==null?"":p.getAddress().getAddress().getHostAddress();Rule r=engine.denial(p.getUniqueId(),ip,currentTokens.getOrDefault(p.getUniqueId(),Set.of()),System.currentTimeMillis());
            if(r!=null){if(possibleEvasion)evade(p.getUniqueId(),p.getName(),r);p.kickPlayer(banMessage(r));}
        }refreshPermissions();
    }
    private void evade(UUID id,String name,Rule rule){
        String key=id+":"+rule.id();long now=System.currentTimeMillis();webhookLimits.entrySet().removeIf(e->e.getValue()<now-300000);
        if(webhookLimits.size()>4096||webhookLimits.putIfAbsent(key,now)!=null)return;
        audit("Security","BLOCKED_LOGIN",name+" / "+id+" / "+rule.id());String url=webhook;
        if(url.isBlank()||!url.matches("https://(?:(?:canary|ptb)\\.)?discord(?:app)?\\.com/api/webhooks/[0-9]+/[A-Za-z0-9_-]+"))return;
        network(()->{try{
            com.google.gson.JsonObject json=new com.google.gson.JsonObject();json.addProperty("content","Blocked login / possible ban evasion: "+clean(name,80)+" ("+id+") | Ban "+rule.id()+" | "+rule.kind());com.google.gson.JsonObject mentions=new com.google.gson.JsonObject();mentions.add("parse",new com.google.gson.JsonArray());json.add("allowed_mentions",mentions);
            webHttp.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.toString())).build(),HttpResponse.BodyHandlers.discarding());
        }catch(Exception e){getLogger().fine("Optional Discord webhook delivery failed");}});
    }
    void refreshPermissions(){
        if(engine==null)return;long now=System.currentTimeMillis();
        for(Player p:Bukkit.getOnlinePlayers()){
            Map<String,Boolean> values=new TreeMap<>();Set<String>denies=new HashSet<>();
            for(Rule r:engine.effective(p.getUniqueId(),now)){
                if(r.kind()==Kind.PERMISSION){if(Boolean.parseBoolean(r.value()))values.put(r.selector(),true);else denies.add(r.selector());}
                if(r.kind()==Kind.ROLE)for(String node:getConfig().getStringList("roles."+r.selector()))values.put(node,true);
            }denies.forEach(node->values.put(node,false));
            PermissionAttachment attachment=attachments.computeIfAbsent(p.getUniqueId(),id->p.addAttachment(this));
            if(!attachment.getPermissions().equals(values)){
                for(String old:new ArrayList<>(attachment.getPermissions().keySet()))attachment.unsetPermission(old);
                values.forEach(attachment::setPermission);p.recalculatePermissions();
            }
        }
    }
    boolean restricted(Player p,String flag){return engine.effective(p.getUniqueId(),System.currentTimeMillis()).stream().anyMatch(r->r.kind()==Kind.TEMPLATE&&Arrays.asList(r.value().split(",")).contains(flag));}
    private void denied(Player p){tell(p,"This action is temporarily restricted by server staff. Use /salrestrictions to see the expiry.");}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void breakBlock(BlockBreakEvent e){if(restricted(e.getPlayer(),"building")){e.setCancelled(true);denied(e.getPlayer());}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void placeBlock(BlockPlaceEvent e){if(restricted(e.getPlayer(),"building")){e.setCancelled(true);denied(e.getPlayer());}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void interact(PlayerInteractEvent e){if(restricted(e.getPlayer(),"interactions")){e.setCancelled(true);denied(e.getPlayer());}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void interactEntity(PlayerInteractEntityEvent e){if(restricted(e.getPlayer(),"interactions")){e.setCancelled(true);denied(e.getPlayer());}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void inventory(InventoryOpenEvent e){if(e.getPlayer() instanceof Player p&&restricted(p,"inventory")&&!(e.getInventory().getHolder() instanceof Menus.View)){e.setCancelled(true);denied(p);}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void chat(AsyncPlayerChatEvent e){if(restricted(e.getPlayer(),"chat")&&!menus.awaiting(e.getPlayer().getUniqueId())){e.setCancelled(true);sync(()->denied(e.getPlayer()));}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void commands(PlayerCommandPreprocessEvent e){if(restricted(e.getPlayer(),"commands")&&!e.getMessage().equalsIgnoreCase("/salrestrictions")){e.setCancelled(true);denied(e.getPlayer());}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void combat(EntityDamageByEntityEvent e){Player p=e.getDamager() instanceof Player v?v:e.getDamager() instanceof org.bukkit.entity.Projectile proj&&proj.getShooter() instanceof Player v?v:null;if(p!=null&&restricted(p,"combat")){e.setCancelled(true);denied(p);}}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[]args){
        if(command.getName().equalsIgnoreCase("salrestrictions")){
            if(sender instanceof Player p)for(Rule r:engine.effective(p.getUniqueId(),System.currentTimeMillis()))if(Set.of(Kind.PERMISSION,Kind.TEMPLATE).contains(r.kind()))tell(sender,r.selector()+" | "+when(r.until())+" | "+r.reason());return true;
        }
        if(!require(sender,"access"))return true;
        try{
            if(args.length==0){if(sender instanceof Player p)menus.home(p);else help(sender);return true;}
            switch(args[0].toLowerCase(Locale.ROOT)){
                case "help"->help(sender);
                case "reload"->{if(require(sender,"settings")){menus.closeAll();reloadConfig();loadSettings();crossplay.connect();startBridge();refreshPermissions();audit(sender.getName(),"RELOAD","Configuration reloaded");tell(sender,"Lite configuration reloaded. The profile database was retained.");}}
                case "updates"->{if(require(sender,"updates")){updates.check();tell(sender,updates.status);}}
                case "player"->{if(!require(sender,"view"))break;if(!(sender instanceof Player p))throw new IllegalArgumentException("Open player menus in-game");if(args.length!=2)throw new IllegalArgumentException("/sal player <name|uuid>");UUID id=engine.resolve(args[1]);if(id==null||engine.profile(id)==null)throw new IllegalArgumentException("No registered profile found");menus.profile(p,id);}
                case "ban"->{if(!require(sender,"ban"))break;if(args.length<6)throw new IllegalArgumentException("/sal ban <name|uuid> <account|ultra> <1h|7d|permanent> <inherit:true|false> <reason>");UUID id=engine.resolve(args[1]);if(id==null)throw new IllegalArgumentException("Use a registered player name or UUID");if(!Set.of("account","ultra").contains(args[2]))throw new IllegalArgumentException("Ban mode: account or ultra");if(!Set.of("true","false").contains(args[4]))throw new IllegalArgumentException("Inheritance must be true or false");Rule r=Rule.make(Kind.BAN,id,"",args[2],expiry(args[3],System.currentTimeMillis()),Boolean.parseBoolean(args[4]),String.join(" ",Arrays.copyOfRange(args,5,args.length)),sender.getName());task(sender,()->{engine.add(r);return r;},v->{enforce();tell(sender,"Ban saved: "+v.id());});}
                case "unban"->{if(!require(sender,"ban"))break;if(args.length!=2)throw new IllegalArgumentException("/sal unban <rule-id>");Rule r=engine.rule(args[1]);if(r==null||!Set.of(Kind.BAN,Kind.IP,Kind.DEVICE).contains(r.kind()))throw new IllegalArgumentException("Active ban rule not found");task(sender,()->{engine.revoke(r.id(),sender.getName());return true;},v->{enforce();tell(sender,"Ban revoked, including device bindings derived from that IP ban.");});}
                case "kick"->{if(!require(sender,"kick"))break;if(args.length<3)throw new IllegalArgumentException("/sal kick <online-player> <reason>");Player p=Bukkit.getPlayerExact(args[1]);if(p==null)throw new IllegalArgumentException("Player is not online");String reason=clean(String.join(" ",Arrays.copyOfRange(args,2,args.length)),512);audit(sender.getName(),"KICK",p.getUniqueId()+" / "+reason);p.kickPlayer(reason);tell(sender,"Player kicked.");}
                default->help(sender);
            }
        }catch(IllegalArgumentException e){tell(sender,e.getMessage());}return true;
    }
    private void help(CommandSender s){tell(s,"/sal — Open the security menu");tell(s,"/sal player <name|uuid> | /sal reload | /sal updates");tell(s,"/sal ban <name|uuid> <account|ultra> <duration> <true|false> <reason>");tell(s,"/sal unban <rule-id> | /sal kick <player> <reason>");}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String alias,String[]a){
        if(!allowed(s,"access"))return List.of();List<String>v=new ArrayList<>();
        if(a.length==1)v.addAll(List.of("help","player","ban","unban","kick","reload","updates"));
        else if(a.length==2&&Set.of("player","ban","kick").contains(a[0]))for(Profile p:engine.profiles())v.add(p.name());
        else if(a.length==3&&a[0].equals("ban"))v.addAll(List.of("account","ultra"));else if(a.length==4&&a[0].equals("ban"))v.addAll(List.of("1h","1d","7d","permanent"));else if(a.length==5&&a[0].equals("ban"))v.addAll(List.of("true","false"));
        String filter=a[a.length-1].toLowerCase(Locale.ROOT);return v.stream().filter(x->x.toLowerCase(Locale.ROOT).startsWith(filter)).limit(100).toList();
    }
}
