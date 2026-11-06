package com.gathin.tvhome;

import android.content.Context;
import android.net.Uri;
import org.json.*;
import java.util.*;
import java.io.*;

/** Independent public catalogs, cached separately so a failed source cannot erase the others. */
final class BuiltinWallpapers {
    static final String[] IDS={"local","aerial","nasa","commons","catalog","coverr","pixabay","pexels","unsplash","wallhaven","motionbgs","mylivewallpapers"};
    static final String[] NAMES={"Локальные видео","Aerial Community","NASA","Wikimedia Commons","Собственный HTTPS-каталог","Coverr","Pixabay","Pexels","Unsplash","Wallhaven","MotionBGS / MoeWalls","MyLiveWallpapers"};
    static final String[] DETAILS={"Видео из локальной медиатеки ТВ","Пейзажи · 1080p H.264 · Jetson Creative","Земля и космос · NASA Image and Video Library","Пейзажи · автор и лицензия в названии ролика","JSON со ссылками на видео","Импорт скачанных видео из папки","Импорт скачанных видео из папки","Импорт скачанных видео из папки","Импорт скачанных изображений из папки","Импорт скачанных изображений из папки","Импорт скачанных видео из папки","Импорт скачанных видео из папки"};
    static boolean enabled(Context c,String id){return WallpaperSource.prefs(c).getBoolean("wallpaper_source_"+id,"aerial".equals(id));}
    static List<WallpaperSource.Item> collection(Context c){
        List<WallpaperSource.Item> out=new ArrayList<>();
        for(String id:IDS){if(!enabled(c,id))continue;
            String folder=WallpaperSource.prefs(c).getString("wallpaper_import_"+id,null);
            if(isImport(id)){if(folder!=null)for(WallpaperSource.Item i:WallpaperSource.media(c))if(WallpaperSource.folderMatches(folder,i.folder)&&i.video!=Arrays.asList("unsplash","wallhaven").contains(id))out.add(i);continue;}
            if(id.equals("local")){for(WallpaperSource.Item i:WallpaperSource.media(c))if(i.video)out.add(i);continue;}
            File cache=new File(c.getCacheDir(),"wallpaper-catalog-"+id+".json");
            JSONArray entries=null;
            try{if(cache.isFile()&&System.currentTimeMillis()-cache.lastModified()<86400000L)entries=new JSONArray(read(cache));}catch(Exception ignored){}
            if(entries==null)try{entries=id.equals("catalog")?new JSONObject(WebData.get(WallpaperSource.prefs(c).getString("wallpaper_catalog_url",""),null)).getJSONArray("items"):fetch(id);try(FileOutputStream f=new FileOutputStream(cache)){f.write(entries.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Exception failure){try{entries=new JSONArray(read(cache));}catch(Exception ignored){}}
            if(entries!=null)for(int n=0;n<entries.length();n++){JSONObject e=entries.optJSONObject(n);if(e==null)continue;String url=e.optString("url");if(url.startsWith("https://"))out.add(new WallpaperSource.Item(Uri.parse(url),e.optString("name"),id,0,true));}
        }
        return out;
    }
    static boolean isImport(String id){return !Arrays.asList("local","aerial","nasa","commons","catalog").contains(id);}
    private static String read(File f)throws IOException{try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(f.length()>4*1024*1024)throw new IOException();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private static void add(JSONArray out,String url,String name)throws JSONException{if(url.startsWith("https://"))out.put(new JSONObject().put("url",url).put("name",name));}
    private static JSONArray fetch(String id)throws Exception{
        JSONArray out=new JSONArray();
        if(id.equals("aerial")){
            JSONArray assets=new JSONObject(WebData.get("https://raw.githubusercontent.com/AerialScreensaver/AerialCommunity/master/entries.json",null)).getJSONArray("assets");
            for(int i=0;i<assets.length();i++){JSONObject a=assets.getJSONObject(i);add(out,a.optString("url-1080-H264"),a.optString("title")+" · Jetson Creative · jetsoncreative.com/aeriallicense");}
        }else if(id.equals("nasa")){
            JSONArray items=new JSONObject(WebData.get("https://images-api.nasa.gov/search?q=earth&media_type=video&page_size=6",null)).getJSONObject("collection").getJSONArray("items");
            for(int i=0;i<Math.min(6,items.length());i++)try{
                JSONObject a=items.getJSONObject(i);JSONArray files=new JSONArray(WebData.get(a.getString("href"),null));String selected="";
                for(int j=0;j<files.length();j++){String u=files.optString(j).replace("http://images-assets.nasa.gov/","https://images-assets.nasa.gov/");if(u.startsWith("https://")&&u.endsWith(".mp4")&&(selected.isEmpty()||u.contains("~medium")))selected=u;}
                add(out,selected,a.getJSONArray("data").getJSONObject(0).optString("title")+" · NASA");
            }catch(Exception ignored){}
        }else if(id.equals("commons")){
            String url="https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrsearch="+Uri.encode("filetype:video landscape nature")+"&gsrnamespace=6&gsrlimit=8&prop=imageinfo&iiprop=url%7Cextmetadata";
            JSONObject pages=new JSONObject(WebData.get(url,null)).getJSONObject("query").getJSONObject("pages");
            Iterator<String> keys=pages.keys();while(keys.hasNext()){JSONObject a=pages.getJSONObject(keys.next());JSONArray info=a.optJSONArray("imageinfo");if(info==null)continue;JSONObject first=info.getJSONObject(0),meta=first.optJSONObject("extmetadata");String license=meta==null?"":meta.optJSONObject("LicenseShortName")==null?"":meta.getJSONObject("LicenseShortName").optString("value");String author=meta==null?"":meta.optJSONObject("Artist")==null?"":android.text.Html.fromHtml(meta.getJSONObject("Artist").optString("value"),0).toString();
                add(out,first.optString("url"),a.optString("title")+" · "+author+" · "+license+" · "+first.optString("descriptionurl"));}
        }
        if(out.length()==0)throw new IOException("Empty catalog");return out;
    }
    private BuiltinWallpapers(){}
}
