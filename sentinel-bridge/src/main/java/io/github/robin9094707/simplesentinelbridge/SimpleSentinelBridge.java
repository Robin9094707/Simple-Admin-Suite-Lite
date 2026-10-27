package io.github.robin9094707.simplesentinelbridge;

import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionLoginEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Geyser extension, not a Bukkit plugin. Only forwards signed session metadata to the Lite suite. */
public final class SimpleSentinelBridge implements Extension {
    private Properties config;private ScheduledThreadPoolExecutor worker;private URI target;
    private final Map<String,GeyserConnection> sessions=new ConcurrentHashMap<>();
    private volatile long lastWarning;
    @Subscribe public void initialize(GeyserPostInitializeEvent event){
        try{
            Files.createDirectories(dataFolder());Path path=dataFolder().resolve("bridge.properties");
            if(!Files.exists(path))try(InputStream in=getClass().getResourceAsStream("/bridge.properties")){Files.copy(Objects.requireNonNull(in),path);}
            config=new Properties();try(InputStream in=Files.newInputStream(path)){config.load(in);}
            String host=config.getProperty("host","127.0.0.1");
            if(!InetAddress.getByName(host).isLoopbackAddress())throw new IllegalArgumentException("Use a loopback host or a local SSH tunnel.");
            if(!config.getProperty("shared-secret","").matches("[0-9a-fA-F]{64}"))throw new IllegalArgumentException("Copy bridge-link.properties from SimpleAdminSuiteLite as this extension's bridge.properties, then restart Geyser.");
            target=new URI("http",null,host,Integer.parseInt(config.getProperty("port","39093")),"/v1/session",null,null);
            worker=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"SimpleSentinelBridge");t.setDaemon(true);return t;});worker.setRemoveOnCancelPolicy(true);
            worker.scheduleWithFixedDelay(this::heartbeat,2,60,TimeUnit.SECONDS);
            logger().info("SimpleSentinelBridge 1.0.0 ready. Bedrock client metadata is reported data, not hardware attestation.");
        }catch(Exception error){logger().error("SimpleSentinelBridge initialization failed: "+error.getMessage());}
    }
    @Subscribe public void login(SessionLoginEvent event){
        GeyserConnection connection=event.connection();if(worker==null||connection==null)return;
        // Bound the scheduled queue during connection floods; periodic refresh retries live sessions.
        if(worker.getQueue().size()>512){warn("Session queue is full; this observation was skipped.");return;}
        worker.execute(()->{sessions.put(connection.xuid(),connection);sendSession(connection);});
        worker.schedule(()->{if(active(connection))sendSession(connection);},5,TimeUnit.SECONDS);
    }
    @Subscribe public void shutdown(GeyserShutdownEvent event){if(worker!=null)worker.shutdownNow();sessions.clear();}
    private void heartbeat(){
        try{
            send(envelope(),"/v1/heartbeat");
            // Include sessions that existed before this extension was enabled.
            Object api=Class.forName("org.geysermc.geyser.api.GeyserApi").getMethod("api").invoke(null);
            Object online=invoke(api,"onlineConnections");if(online instanceof Iterable<?> values)for(Object o:values)if(o instanceof GeyserConnection c)sessions.put(c.xuid(),c);
            for(var entry:sessions.entrySet())if(active(entry.getValue()))sendSession(entry.getValue());else sessions.remove(entry.getKey(),entry.getValue());
        }catch(Exception e){warn("Lite suite heartbeat failed: "+e.getMessage());}
    }
    private boolean active(GeyserConnection c){
        try{Object api=Class.forName("org.geysermc.geyser.api.GeyserApi").getMethod("api").invoke(null);return invoke(api,"connectionByXuid",c.xuid())==c;}
        catch(Exception e){return false;}
    }
    private void sendSession(GeyserConnection c){
        try{Properties p=collect(c);send(p,"/v1/session");}catch(Exception e){warn("Session metadata delivery failed: "+e.getMessage());}
    }
    static Properties collect(GeyserConnection c){
        Properties p=envelope();p.setProperty("bridgeVersion","1.0.0");put(p,"xuid",c.xuid());
        Object data=invoke(c,"getClientData");if(data==null)data=invoke(c,"clientData");if(data==null)data=c;
        String[][]fields={{"deviceId","getDeviceId","deviceId"},{"deviceModel","getDeviceModel","deviceModel"},{"deviceOs","getDeviceOs","deviceOs"},{"gameVersion","getGameVersion","gameVersion"},{"languageCode","getLanguageCode","languageCode"},{"selfSignedId","getSelfSignedId","selfSignedId"},{"currentInputMode","getCurrentInputMode","currentInputMode"},{"defaultInputMode","getDefaultInputMode","defaultInputMode"},{"uiProfile","getUiProfile","uiProfile"},{"clientRandomId","getClientRandomId","clientRandomId"}};
        for(String[] f:fields)put(p,f[0],first(data,Arrays.copyOfRange(f,1,f.length)));
        put(p,"gameVersion",first(c,"gameVersion","getGameVersion"));
        put(p,"javaUuid",first(c,"javaUuid","getJavaUuid"));put(p,"name",c.name());
        p.setProperty("collectionSource",data==c?"Geyser public connection API":"Geyser session client data");
        return p;
    }
    private static void put(Properties p,String key,String value){if(value!=null&&!value.isBlank()&&value.length()<=256)p.setProperty(key,value.replaceAll("[\\p{Cntrl}]"," "));}
    private static String first(Object target,String...names){for(String name:names){Object result=invoke(target,name);if(result!=null&&!result.toString().isBlank())return result.toString();}return "";}
    private static Object invoke(Object object,String method,Object...args){
        if(object==null)return null;for(Method m:object.getClass().getMethods())if(m.getName().equals(method)&&m.getParameterCount()==args.length)try{if(!m.canAccess(object))m.trySetAccessible();return m.invoke(object,args);}catch(ReflectiveOperationException|IllegalArgumentException ignored){}return null;
    }
    static Properties envelope(){Properties p=new Properties();p.setProperty("timestamp",""+System.currentTimeMillis());p.setProperty("nonce",UUID.randomUUID().toString());return p;}
    static String hmac(byte[] bytes,String secret) throws Exception {Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(m.doFinal(bytes));}
    private void send(Properties p,String path) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();p.store(out,"Simple Sentinel Bridge");byte[] body=out.toByteArray();
        URI uri=new URI(target.getScheme(),null,target.getHost(),target.getPort(),path,null,null);
        HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();c.setConnectTimeout(1500);c.setReadTimeout(2000);c.setRequestMethod("POST");c.setInstanceFollowRedirects(false);c.setDoOutput(true);
        c.setRequestProperty("Content-Type","text/plain");c.setRequestProperty("X-Simple-Signature",hmac(body,config.getProperty("shared-secret")));c.setFixedLengthStreamingMode(body.length);
        try{
            try(OutputStream stream=c.getOutputStream()){stream.write(body);}int code=c.getResponseCode();
            if(code!=200)throw new IOException("HTTP "+code);
            byte[] response;try(InputStream in=c.getInputStream()){response=in.readNBytes(16385);}
            String signature=c.getHeaderField("X-Simple-Response-Signature");
            if(response.length>16384||signature==null||!MessageDigest.isEqual(hmac(response,config.getProperty("shared-secret")).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)))throw new IOException("Invalid response signature");
        }finally{c.disconnect();}
    }
    private void warn(String message){long now=System.currentTimeMillis();if(now-lastWarning>60000){lastWarning=now;logger().warning(message);}}
}
