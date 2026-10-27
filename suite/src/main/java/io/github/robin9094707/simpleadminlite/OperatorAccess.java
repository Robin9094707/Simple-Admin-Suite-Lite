package io.github.robin9094707.simpleadminlite;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Bukkit's OP flag alone does not distinguish a limited level-1/2/3 operator from full level 4. */
final class OperatorAccess {
    private final Path file;private Map<UUID,Integer> levels=Map.of();private long modified=-1,size=-1;
    OperatorAccess(Path file){this.file=file;}
    synchronized boolean full(UUID id){
        try{
            BasicFileAttributes attributes=Files.readAttributes(file,BasicFileAttributes.class);
            long time=attributes.lastModifiedTime().toMillis(),length=attributes.size();
            if(time!=modified||length!=size){
                if(length>1048576)throw new IllegalArgumentException("Operator list is too large");Map<UUID,Integer> next=new HashMap<>();
                for(JsonElement row:JsonParser.parseString(Files.readString(file)).getAsJsonArray()){
                    JsonObject object=row.getAsJsonObject();next.put(UUID.fromString(object.get("uuid").getAsString()),object.get("level").getAsInt());
                }levels=Map.copyOf(next);modified=time;size=length;
            }return levels.getOrDefault(id,0)>=4;
        }catch(Exception e){levels=Map.of();modified=-1;size=-1;return false;}
    }
}
