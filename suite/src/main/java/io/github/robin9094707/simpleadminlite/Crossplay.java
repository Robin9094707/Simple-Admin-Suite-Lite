package io.github.robin9094707.simpleadminlite;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

/** Loads optional Floodgate/Geyser APIs from their owning classloader. No bundled API copies. */
final class Crossplay {
    private final SimpleAdminLite plugin;
    private Object api;private ClassLoader loader;private String test,lookup;
    Crossplay(SimpleAdminLite plugin){this.plugin=plugin;connect();}
    void connect(){
        api=null;for(String name:List.of("floodgate","Floodgate","Geyser-Spigot","Geyser")){
            Plugin p=Bukkit.getPluginManager().getPlugin(name);if(p==null||!p.isEnabled())continue;
            boolean flood=name.toLowerCase(Locale.ROOT).contains("floodgate");
            try{
                loader=p.getClass().getClassLoader();Class<?> c=Class.forName(flood?"org.geysermc.floodgate.api.FloodgateApi":"org.geysermc.geyser.api.GeyserApi",true,loader);
                api=c.getMethod(flood?"getInstance":"api").invoke(null);test=flood?"isFloodgatePlayer":"isBedrockPlayer";lookup=flood?"getPlayer":"connectionByUuid";
                for(Method m:c.getMethods())if(m.getName().equals("sendForm")&&m.getParameterCount()==2&&m.getParameterTypes()[1].getName().startsWith("org.geysermc.cumulus.")){loader=m.getParameterTypes()[1].getClassLoader();break;}
                return;
            }catch(ReflectiveOperationException|LinkageError e){api=null;}
        }
    }
    static Object call(Object object,String method,Object...args) throws ReflectiveOperationException {
        if(object==null)throw new NoSuchMethodException(method);
        for(Method m:object.getClass().getMethods())if(m.getName().equals(method)&&m.getParameterCount()==args.length){
            boolean fits=true;Class<?>[]types=m.getParameterTypes();for(int i=0;i<args.length;i++)if(args[i]!=null&&!box(types[i]).isInstance(args[i])){fits=false;break;}
            if(fits){if(!m.canAccess(object))m.trySetAccessible();return m.invoke(object,args);}
        }throw new NoSuchMethodException(method);
    }
    private static Class<?> box(Class<?> c){return c==boolean.class?Boolean.class:c==int.class?Integer.class:c==long.class?Long.class:c==float.class?Float.class:c;}
    static String text(Object object,String...names){for(String name:names)try{Object o=call(object,name);if(o!=null&&!o.toString().isBlank())return Model.clean(o.toString(),256);}catch(ReflectiveOperationException|RuntimeException ignored){}return "";}
    boolean bedrock(UUID id){try{return api!=null&&Boolean.TRUE.equals(call(api,test,id));}catch(ReflectiveOperationException e){return false;}}
    Object player(UUID id){try{return api==null?null:call(api,lookup,id);}catch(ReflectiveOperationException e){return null;}}
    String xuid(UUID id){return text(player(id),"getXuid","xuid");}
    Map<String,String> metadata(UUID id){
        if(!bedrock(id))return Map.of();Object p=player(id);Map<String,String>out=new LinkedHashMap<>();
        out.put("deviceOs",text(p,"getDeviceOs","platform","bedrockPlatform"));out.put("currentInputMode",text(p,"getInputMode","inputMode"));
        out.put("gameVersion",text(p,"getVersion","version","clientVersion"));out.put("languageCode",text(p,"getLanguageCode","locale"));
        return out;
    }
    private Object builder(String type,String title) throws Exception {
        Class<?> form=Class.forName("org.geysermc.cumulus.form."+type,true,loader);return call(form.getMethod("builder").invoke(null),"title","§0"+title);
    }
    boolean menu(Player p,String title,String body,List<Menus.Button>buttons,long generation){
        if(!bedrock(p.getUniqueId()))return false;
        try{
            Object b=builder("SimpleForm",title);b=call(b,"content","§f"+body);
            for(Menus.Button button:buttons){
                try{
                    Class<?> type=Class.forName("org.geysermc.cumulus.util.FormImage$Type",true,loader);
                    @SuppressWarnings({"unchecked","rawtypes"})Object path=Enum.valueOf((Class)type,"PATH");
                    b=call(b,"button","§0"+button.title(),path,button.icon());
                }catch(ReflectiveOperationException ignored){b=call(b,"button","§0"+button.title());}
            }
            Consumer<Object> handler=response->{
                String raw=text(response,"clickedButtonId");int index;try{index=Integer.parseInt(raw);}catch(NumberFormatException e){return;}
                if(index>=0&&index<buttons.size())plugin.sync(()->plugin.menus.callback(p,generation,buttons.get(index).action()));
            };
            b=call(b,"validResultHandler",handler);return send(p,b);
        }catch(Exception|LinkageError e){plugin.getLogger().warning("Native Bedrock menu unavailable: "+e.getClass().getSimpleName()+"; using Java inventory fallback.");return false;}
    }
    boolean input(Player p,String title,List<String>labels,List<String>defaults,long generation,Consumer<List<String>>handler){
        if(!bedrock(p.getUniqueId()))return false;
        try{
            Object b=builder("CustomForm",title);for(int i=0;i<labels.size();i++)b=call(b,"input","§0"+labels.get(i),"",defaults.get(i));
            b=call(b,"validResultHandler",(Consumer<Object>)response->{
                List<String>out=new ArrayList<>();try{for(int i=0;i<labels.size();i++)out.add(Model.clean(String.valueOf(call(response,"asInput",i)),1024));}catch(ReflectiveOperationException e){return;}
                plugin.sync(()->plugin.menus.callback(p,generation,()->handler.accept(out)));
            });return send(p,b);
        }catch(Exception|LinkageError e){return false;}
    }
    private boolean send(Player p,Object builder) throws ReflectiveOperationException {
        Object form=call(builder,"build");Object result=call(api,"sendForm",p.getUniqueId(),form);return !(result instanceof Boolean)||Boolean.TRUE.equals(result);
    }
}
