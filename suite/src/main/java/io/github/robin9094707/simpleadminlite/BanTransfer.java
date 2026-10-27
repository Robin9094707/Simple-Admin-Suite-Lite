package io.github.robin9094707.simpleadminlite;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static io.github.robin9094707.simpleadminlite.Model.*;

/** Read-only import adapters. A preview captures immutable rows; imports are explicitly selected. */
public final class BanTransfer {
    public record Preview(String source,List<ImportRow> rows,List<String> skipped,long created){
        public Preview{rows=List.copyOf(rows);skipped=List.copyOf(skipped);}
    }
    public static Preview vanilla(Path root,String actor) throws Exception {
        List<ImportRow> rows=new ArrayList<>();List<String>skipped=new ArrayList<>();long now=System.currentTimeMillis();
        for(String file:List.of("banned-players.json","banned-ips.json")){
            Path path=root.resolve(file);if(!Files.isRegularFile(path))continue;
            if(Files.size(path)>32*1024*1024)throw new IOException("Ban list exceeds the 32 MiB preview limit");
            JsonElement json=JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8));if(!json.isJsonArray())throw new IOException("Invalid "+file);
            int rowNo=0;for(JsonElement element:json.getAsJsonArray()){
                rowNo++;try{
                    JsonObject row=element.getAsJsonObject();long until=vanillaDate(get(row,"expires","forever"));
                    if(until!=0&&until<=now){skipped.add(file+" #"+rowNo+": expired");continue;}
                    boolean address=file.equals("banned-ips.json");UUID target=address?null:UUID.fromString(get(row,"uuid",""));
                    String selector=address?Model.ip(get(row,"ip","")):"";
                    String name=address?selector:get(row,"name",target.toString());String reason=get(row,"reason","Imported ban");
                    Rule r=new Rule(UUID.randomUUID().toString(),address?Kind.IP:Kind.BAN,target,selector,"account",now,until,true,reason,actor,"");
                    String key="vanilla:"+file+":"+(address?selector:target)+":"+get(row,"created","")+":"+until+":"+Crypto.sha(reason.getBytes(StandardCharsets.UTF_8));
                    rows.add(new ImportRow(key,r,name,address?"Exact IP ban; observed Bedrock devices will be bound to this ban.":"Account ban; strong linked accounts inherit by default."));
                }catch(RuntimeException error){skipped.add(file+" #"+rowNo+": "+clean(error.getMessage(),180));}
            }
        }return new Preview("Minecraft ban lists",rows,skipped,now);
    }
    private static String get(JsonObject row,String key,String fallback){JsonElement value=row.get(key);return value==null||value.isJsonNull()?fallback:value.getAsString();}
    static long vanillaDate(String value){
        if(value==null||value.isBlank()||value.equalsIgnoreCase("forever"))return 0;
        for(DateTimeFormatter format:List.of(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z",Locale.ROOT),DateTimeFormatter.ISO_OFFSET_DATE_TIME))try{return OffsetDateTime.parse(value,format).toInstant().toEpochMilli();}catch(DateTimeException ignored){}
        try{return Instant.parse(value).toEpochMilli();}catch(DateTimeException e){throw new IllegalArgumentException("Unsupported ban expiry: "+value);}
    }
    public static Preview liteBans(Path file,String prefix,String actor) throws Exception {
        if(!prefix.matches("[A-Za-z0-9_]{0,50}"))throw new IllegalArgumentException("Invalid LiteBans table prefix");
        Path source=file.toRealPath();if(!Files.isRegularFile(source))throw new IOException("LiteBans database file not found");
        String filename=source.getFileName().toString().toLowerCase(Locale.ROOT);String url;
        if(filename.endsWith(".mv.db")){
            Class.forName("org.h2.Driver");String base=source.toString().substring(0,source.toString().length()-6);
            if(base.contains(";"))throw new IOException("Unsupported database path");
            url="jdbc:h2:file:"+base+";ACCESS_MODE_DATA=r;IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        }else if(filename.endsWith(".db")||filename.endsWith(".sqlite")||filename.endsWith(".sqlite3")){
            Class.forName("org.sqlite.JDBC");url="jdbc:sqlite:"+source.toUri()+"?mode=ro";
        }else throw new IOException("Supported local databases: H2 .mv.db or SQLite .db/.sqlite. Supply a closed, consistent database snapshot; remote SQL is not accessed.");
        try(Connection connection=DriverManager.getConnection(url,filename.endsWith(".mv.db")?"sa":"","")){
            return liteBans(connection,prefix,source.toString(),actor);
        }catch(SQLException e){throw new IOException("Cannot read the LiteBans database snapshot. Stop LiteBans before copying it. Encrypted or older incompatible H2 files require a compatible export. "+clean(e.getMessage(),180),e);}
    }
    static Preview liteBans(Connection connection,String prefix,String sourceKey,String actor) throws Exception {
        if(!prefix.matches("[A-Za-z0-9_]{0,50}"))throw new IllegalArgumentException("Invalid table prefix");
        List<ImportRow> rows=new ArrayList<>();List<String> skipped=new ArrayList<>();long now=System.currentTimeMillis();
        // ipban is authoritative: a recorded IP on an account-only ban must never become an IP ban.
        String sql="SELECT id,uuid,ip,reason,until,active,ipban FROM "+prefix+"bans ORDER BY id";
        try(Statement s=connection.createStatement()){
            s.setQueryTimeout(20);s.setMaxRows(100001);try(ResultSet rs=s.executeQuery(sql)){
                int count=0;while(rs.next()){
                    if(++count>100000)throw new IOException("Preview exceeds 100,000 rows; create a smaller export.");
                    String external=rs.getString("id");try{
                        if(!rs.getBoolean("active"))continue;long until=rs.getLong("until");if(until<=0)until=0;
                        if(until!=0&&until<=now){skipped.add("#"+external+": expired");continue;}
                        boolean ipban=rs.getBoolean("ipban");UUID id=ipban?null:UUID.fromString(rs.getString("uuid"));String selector=ipban?Model.ip(rs.getString("ip")):"";
                        String reason=Objects.requireNonNullElse(rs.getString("reason"),"Imported ban");
                        Rule r=new Rule(UUID.randomUUID().toString(),ipban?Kind.IP:Kind.BAN,id,selector,"account",now,until,true,reason,actor,"");
                        String key="litebans:"+sourceKey+":"+external+":"+Objects.toString(id,selector)+":"+until+":"+Crypto.sha(reason.getBytes(StandardCharsets.UTF_8));
                        rows.add(new ImportRow(key,r,"LiteBans #"+external+" | "+(ipban?selector:id),ipban?"Exact IP ban; observed Bedrock devices inherit this ban.":"UUID ban; its recorded IP is not imported as a ban."));
                    }catch(RuntimeException error){skipped.add("#"+external+": "+clean(error.getMessage(),200));}
                }
            }
        }return new Preview("LiteBans read-only snapshot",rows,skipped,now);
    }
}
