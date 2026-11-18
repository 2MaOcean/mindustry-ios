package mindustry.ios.resource;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Client for the public MindustryTool API (https://api.mindustry-tool.com/api/v4). */
public class MindustryToolApi{
    public static final String BASE = "https://api.mindustry-tool.com/api/v4";

    public static class Item{
        public String id, itemId, name;
        public int likes, downloads, comments;
        public String raw;
    }

    public static class Tag{
        public String name, color;
    }

    /** synchronous fetch; call from a background thread */
    public static List<Item> search(String kind, int page, int size, String sort, String name) throws Exception{
        String url = BASE + "/" + kind + "?page=" + page + "&size=" + Math.min(size, 100);
        if(sort != null && !sort.isEmpty()) url += "&sort=" + enc(sort);
        if(name != null && !name.isEmpty()) url += "&name=" + enc(name);
        String body = httpGet(url);
        List<Item> out = new ArrayList<>();
        for(Object o : (List<?>)parseArray(body)){
            out.add(fromJson(o));
        }
        return out;
    }

    public static List<Tag> tags(String group) throws Exception{
        String body = httpGet(BASE + "/tags?group=" + enc(group));
        List<Tag> out = new ArrayList<>();
        for(Object o : (List<?>)parseArray(body)){
            Tag t = new Tag();
            t.name = str(field(o, "name"));
            t.color = str(field(o, "color"));
            out.add(t);
        }
        return out;
    }

    /** downloads the raw map/schematic data for an item */
    public static byte[] download(String kind, String itemId) throws Exception{
        return httpGetBytes(BASE + "/" + kind + "/" + itemId + "/data");
    }

    public static byte[] image(String kind, String itemId, boolean preview) throws Exception{
        return httpGetBytes(BASE + "/" + kind + "/" + itemId + "/image.png" + (preview ? "?variant=preview" : ""));
    }

    //region http & json helpers (no external deps)

    static String httpGet(String url) throws Exception{
        return new String(httpGetBytes(url), StandardCharsets.UTF_8);
    }

    static byte[] httpGetBytes(String url) throws Exception{
        for(int attempt = 0; attempt < 3; attempt++){
            try{
                HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "mindustry-ios-resource-center");
                c.setRequestProperty("Accept", "application/json, */*");
                int code = c.getResponseCode();
                if(code >= 400) throw new IOException("HTTP " + code + " for " + url);
                try(InputStream in = c.getInputStream()){
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    return out.toByteArray();
                }finally{
                    c.disconnect();
                }
            }catch(Exception e){
                if(attempt == 2) throw e;
                Thread.sleep(1500);
            }
        }
        throw new IOException("unreachable");
    }

    static List<?> parseArray(String json) throws Exception{
        List<Object> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false, esc = false;
        for(int i = 0; i < json.length(); i++){
            char c = json.charAt(i);
            if(esc){ esc = false; continue; }
            if(c == '\\'){ esc = true; continue; }
            if(c == '"') inStr = !inStr;
            if(inStr) continue;
            if(c == '{'){
                if(depth == 0) start = i;
                depth++;
            }else if(c == '}'){
                depth--;
                if(depth == 0 && start >= 0){
                    out.add(parseObject(json.substring(start, i + 1)));
                    start = -1;
                }
            }
        }
        return out;
    }

    static Map<String, Object> parseObject(String json){
        Map<String, Object> map = new HashMap<>();
        int i = 1;
        while(i < json.length() - 1){
            int colon = json.indexOf(':', i);
            if(colon < 0) break;
            String key = unquote(json.substring(i, colon).trim());
            int vstart = colon + 1;
            while(vstart < json.length() && (json.charAt(vstart) == ' ')) vstart++;
            if(vstart >= json.length() - 1) break;
            char vc = json.charAt(vstart);
            if(vc == '"'){
                int end = -1;
                boolean esc2 = false;
                for(int k = vstart + 1; k < json.length(); k++){
                    char ch = json.charAt(k);
                    if(esc2){ esc2 = false; continue; }
                    if(ch == '\\'){ esc2 = true; continue; }
                    if(ch == '"'){ end = k; break; }
                }
                if(end < 0) break;
                map.put(key, unquote(json.substring(vstart, end + 1)));
                i = end + 1;
                if(i < json.length() && json.charAt(i) == ',') i++;
            }else{
                int end = json.indexOf(',', vstart);
                int brace = json.indexOf('}', vstart);
                if(end < 0 || (brace >= 0 && brace < end)) end = brace;
                if(end < 0) break;
                map.put(key, json.substring(vstart, end).trim());
                i = end;
                if(i < json.length() && json.charAt(i) == ',') i++;
            }
        }
        return map;
    }

    static String unquote(String s){
        s = s.trim();
        if(s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) return s.substring(1, s.length() - 1);
        return s;
    }

    static Object field(Object obj, String key){
        return obj instanceof Map ? ((Map<?, ?>)obj).get(key) : null;
    }

    static String str(Object o){
        return o == null ? "" : o.toString();
    }

    static Item fromJson(Object o){
        Item it = new Item();
        it.id = str(field(o, "id"));
        it.itemId = str(field(o, "itemId"));
        it.name = str(field(o, "name"));
        Object l = field(o, "likes"); if(l != null) try{ it.likes = Integer.parseInt(str(l)); }catch(Throwable ignored){}
        Object d = field(o, "downloads"); if(d != null) try{ it.downloads = Integer.parseInt(str(d)); }catch(Throwable ignored){}
        it.raw = o.toString();
        return it;
    }

    static String enc(String s){
        try{
            return URLEncoder.encode(s, "UTF-8");
        }catch(UnsupportedEncodingException e){
            return s;
        }
    }

    //endregion
}
