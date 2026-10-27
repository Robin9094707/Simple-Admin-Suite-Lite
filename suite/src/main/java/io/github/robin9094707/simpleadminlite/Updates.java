package io.github.robin9094707.simpleadminlite;

import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.*;

/** Official Geyser downloads only; verified staging, never a live reload or restart. */
final class Updates {
    private final SimpleAdminLite plugin;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final AtomicBoolean busy=new AtomicBoolean();
    volatile String status="Not checked yet";volatile List<String> ready=List.of();
    record Installed(String project,String name,String version,Path file,Path stage,String serverVersion){}
    Updates(SimpleAdminLite plugin){this.plugin=plugin;}
    void check(){
        if(!busy.compareAndSet(false,true))return;
        List<Installed> installed=new ArrayList<>();
        try{
            Path dir=plugin.getDataFolder().getParentFile().toPath().toRealPath();Path stage=Bukkit.getServer().getUpdateFolderFile().toPath().toAbsolutePath().normalize();
            if(stage.equals(dir))throw new IOException("Paper's update folder is disabled; active plugin JARs will not be overwritten.");
            for(String project:List.of("geyser","floodgate")){
                Plugin dependency=Bukkit.getPluginManager().getPlugin(project.equals("geyser")?"Geyser-Spigot":"floodgate");
                if(dependency==null)continue;
                Path original=findOriginal(dir,dependency.getName(),dependency.getDescription().getVersion());
                if(original!=null)installed.add(new Installed(project,dependency.getName(),dependency.getDescription().getVersion(),original,stage,Bukkit.getBukkitVersion()));
            }
        }catch(Exception error){status="Update check unavailable: "+Model.clean(error.getMessage(),200);busy.set(false);return;}
        status="Checking official Geyser/Floodgate builds...";
        plugin.network(()->{
            List<String> staged=new ArrayList<>(),failures=new ArrayList<>();
            for(Installed item:installed)try{String result=stage(item);if(result!=null)staged.add(result);}catch(Exception e){failures.add(item.name()+": "+Model.clean(e.getMessage(),180));}
            ready=List.copyOf(staged);
            status=Model.when(System.currentTimeMillis())+" | "+(installed.isEmpty()?"No local Geyser/Floodgate installation":staged.isEmpty()?"No new compatible updates staged":"Ready for the next scheduled restart: "+String.join(", ",staged))+(failures.isEmpty()?"":" | "+String.join("; ",failures));
            busy.set(false);plugin.getLogger().info(status);
            plugin.sync(()->{for(var p:Bukkit.getOnlinePlayers())if(plugin.allowed(p,"updates")&&!staged.isEmpty())plugin.tell(p,"Geyser/Floodgate updates are ready for the next server restart: "+String.join(", ",staged));});
        });
    }
    private Path findOriginal(Path dir,String name,String version) throws Exception {
        try(var files=Files.list(dir)){
            for(Path file:files.filter(p->p.getFileName().toString().endsWith(".jar")).toList())try(JarFile jar=new JarFile(file.toFile())){
                var descriptor=jar.getJarEntry("plugin.yml");if(descriptor==null)continue;
                YamlConfiguration yaml;try(var in=jar.getInputStream(descriptor)){yaml=YamlConfiguration.loadConfiguration(new InputStreamReader(in,StandardCharsets.UTF_8));}
                if(name.equalsIgnoreCase(yaml.getString("name",""))&&version.equals(yaml.getString("version","")))return file;
            }catch(IOException ignored){}
        }return null;
    }
    private String stage(Installed item) throws Exception {
        URI meta=URI.create("https://download.geysermc.org/v2/projects/"+item.project()+"/versions/latest/builds/latest");
        HttpResponse<InputStream> response=http.send(request(meta,30),HttpResponse.BodyHandlers.ofInputStream());byte[] json;
        try(InputStream in=response.body()){if(response.statusCode()!=200)throw new IOException("Metadata HTTP "+response.statusCode());json=in.readNBytes(1048577);}
        if(json.length>1048576)throw new IOException("Metadata limit exceeded");
        JsonObject data=JsonParser.parseString(new String(json,StandardCharsets.UTF_8)).getAsJsonObject();String version=data.get("version").getAsString();int build=data.get("build").getAsInt();
        if(!version.matches("[0-9A-Za-z.+_-]{1,60}")||build<=0)throw new IOException("Invalid build metadata");
        String expected=data.getAsJsonObject("downloads").getAsJsonObject("spigot").get("sha256").getAsString();
        if(!expected.matches("[0-9a-f]{64}"))throw new IOException("Official SHA-256 is missing");
        if(hash(item.file()).equals(expected))return null;
        Files.createDirectories(item.stage());if(Files.isSameFile(item.stage(),item.file().getParent()))throw new IOException("Unsafe update destination");
        Path target=item.stage().resolve(item.file().getFileName());
        if(Files.isRegularFile(target)&&hash(target).equals(expected))return item.name()+" "+version+" build "+build;
        Path temp=Files.createTempFile(item.stage(),"simple-update-",".part");
        try{
            URI uri=URI.create("https://download.geysermc.org/v2/projects/"+item.project()+"/versions/"+version+"/builds/"+build+"/downloads/spigot");
            HttpResponse<InputStream> download=http.send(request(uri,120),HttpResponse.BodyHandlers.ofInputStream());
            try(InputStream in=download.body();OutputStream out=Files.newOutputStream(temp)){
                if(download.statusCode()!=200)throw new IOException("Download HTTP "+download.statusCode());
                copyLimited(in,out,150L*1024*1024);
            }
            if(!hash(temp).equals(expected))throw new IOException("Official checksum mismatch");
            verifyJar(temp,item.name(),Runtime.version().feature(),item.serverVersion());
            // Avoid replacing a different update placed by another administrator while downloading.
            if(Files.exists(target)&&!hash(target).equals(expected))throw new IOException("A different update is already staged; review it first");
            try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
            return item.name()+" "+version+" build "+build;
        }finally{Files.deleteIfExists(temp);}
    }
    private static HttpRequest request(URI uri,int seconds){return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(seconds)).header("User-Agent","SimpleAdminSuiteLite/1.0.0 (Robin9094707)").GET().build();}
    static void copyLimited(InputStream input,OutputStream output,long max) throws IOException {
        byte[]buffer=new byte[16384];long count=0;int n;while((n=input.read(buffer))!=-1){count+=n;if(count>max)throw new IOException("Download size limit exceeded");output.write(buffer,0,n);}
    }
    static String hash(Path file) throws Exception {MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[]b=new byte[32768];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}
    static void verifyJar(Path path,String name,int java,String serverVersion) throws Exception {
        try(JarFile jar=new JarFile(path.toFile())){
            var entry=jar.getJarEntry("plugin.yml");if(entry==null||entry.getSize()>262144)throw new IOException("Missing plugin.yml");
            YamlConfiguration y;try(InputStream in=jar.getInputStream(entry)){y=YamlConfiguration.loadConfiguration(new InputStreamReader(in,StandardCharsets.UTF_8));}
            if(!name.equalsIgnoreCase(y.getString("name","")))throw new IOException("Unexpected plugin identity");
            String main=y.getString("main","").replace('.','/')+".class";if(jar.getJarEntry(main)==null)throw new IOException("Plugin main class is missing");
            if(compareVersion(y.getString("api-version","1.13"),serverVersion.split("-")[0])>0)throw new IOException("Update requires a newer Minecraft API");
            var entries=jar.entries();while(entries.hasMoreElements()){
                var e=entries.nextElement();if(!e.getName().endsWith(".class")||e.getName().startsWith("META-INF/versions/"))continue;
                try(DataInputStream in=new DataInputStream(jar.getInputStream(e))){if(in.readInt()!=0xcafebabe)throw new IOException("Invalid class file");in.readUnsignedShort();if(in.readUnsignedShort()>44+java)throw new IOException("Update requires a newer Java runtime");}
            }
        }
    }
    static int compareVersion(String a,String b){String[]x=a.split("\\."),y=b.split("\\.");for(int i=0;i<Math.max(x.length,y.length);i++){int one=i<x.length?Integer.parseInt(x[i].replaceAll("[^0-9].*","")):0,two=i<y.length?Integer.parseInt(y[i].replaceAll("[^0-9].*","")):0;if(one!=two)return Integer.compare(one,two);}return 0;}
}
