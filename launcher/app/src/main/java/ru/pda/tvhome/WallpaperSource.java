package ru.pda.tvhome;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.util.*;

/** Local images/video and independent built-in catalogs, with optional Projectivy support. */
final class WallpaperSource {
    static final String MODE="background_source", FILE="background_file", FOLDER="background_folder", MINUTES="background_minutes";
    static final String[] MODES={"Без обоев","Файл","Папка","Встроенные видеообои","Aerial Views"};
    // Rotation interval is a smooth 1..60 slider in the settings now; the old fixed interval list is gone.
    static final long TRANSITION_MS=3000;
    /** Safety limits for a recursive folder walk: a TV's storage can hold thousands of files. */
    static final int MAX_DEPTH=6, MAX_IMAGES=500;

    static final class Item {
        final Uri uri; final String name; final String folder; final boolean video; final int displayMode;
        Item(Uri uri,String name,String folder){this(uri,name,folder,0,false);}
        Item(Uri uri,String name,String folder,int displayMode,boolean video){this.uri=uri;this.name=name;this.folder=folder==null?"":folder;this.displayMode=displayMode;this.video=video;}
    }

    static SharedPreferences prefs(Context c){return c.getSharedPreferences(Config.SETTINGS_NAME,0);}
    static String mode(Context c){return prefs(c).getString(MODE,"none");}
    static int modeIndex(Context c){return modeIndexOf(mode(c));}
    static int minutes(Context c){int v=prefs(c).getInt(MINUTES,15);return Math.max(1,Math.min(60,v));}
    static void changed(Context c){SharedPreferences p=prefs(c);p.edit().putLong(Config.SETTINGS_REVISION_KEY,p.getLong(Config.SETTINGS_REVISION_KEY,0)+1).apply();}
    static void setMode(Context c,String value){prefs(c).edit().putString(MODE,value).apply();changed(c);}

    /** "Режим смены обоев" while the folder is the source: Home shows the next picture now. */
    static void requestNext(Context c){prefs(c).edit().putBoolean(NEXT,true).apply();}
    static boolean consumeNext(Context c){boolean pending=prefs(c).getBoolean(NEXT,false);if(pending)prefs(c).edit().remove(NEXT).apply();return pending;}
    static final String NEXT="background_next";

    static String name(Context c,String key){if(key.equals(FILE)){String label=prefs(c).getString("background_file_label","");if(!label.isEmpty())return label;}String value=prefs(c).getString(key,"");if(value.isEmpty())return "Не выбран";if(value.startsWith("media:"))return value.substring(6);try(Cursor row=c.getContentResolver().query(Uri.parse(value),new String[]{"_display_name"},null,null,null)){if(row!=null&&row.moveToFirst())return row.getString(0);}catch(Exception ignored){}return Uri.parse(value).getLastPathSegment();}

    static List<Item> collection(Context c){
        String mode=mode(c);
        if(mode.equals("builtin")||mode.equals("app"))return BuiltinWallpapers.collection(c);
        if(mode.equals("provider"))return ProjectivyClient.videos(c);
        if(mode.equals("file")){
            String value=prefs(c).getString(FILE,"");if(value.isEmpty())return Collections.emptyList();
            Uri uri=Uri.parse(value);boolean video=isVideo(c.getContentResolver().getType(uri),value);
            return Collections.singletonList(new Item(uri,name(c,FILE),"",0,video));
        }
        if(!mode.equals("folder"))return Collections.emptyList();
        String value=prefs(c).getString(FOLDER,"");if(value.isEmpty())return Collections.emptyList();
        if(value.startsWith("media:")){List<Item> all=media(c);all.removeIf(i->!folderMatches(value.substring(6),i.folder));return all;}
        List<Item> items=new ArrayList<>();Uri tree=Uri.parse(value);
        walk(c,tree,DocumentsContract.getTreeDocumentId(tree),items,0);
        items.sort(Comparator.comparing(i->i.name,String.CASE_INSENSITIVE_ORDER));return items;
    }

    /** Recursive SAF walk: image and video children, with a depth and a total-count limit. */
    private static void walk(Context c,Uri tree,String parentId,List<Item> out,int depth){
        if(depth>MAX_DEPTH||out.size()>=MAX_IMAGES)return;
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId);
        List<String> dirs=new ArrayList<>();
        try(Cursor rows=c.getContentResolver().query(children,new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            while(rows!=null&&rows.moveToNext()&&out.size()<MAX_IMAGES){
                String id=rows.getString(0),name=rows.getString(1),type=rows.getString(2);
                if(type!=null&&type.equals(DocumentsContract.Document.MIME_TYPE_DIR)){dirs.add(id);continue;}
                if(type!=null&&(type.startsWith("image/")||type.startsWith("video/")))out.add(new Item(DocumentsContract.buildDocumentUriUsingTree(tree,id),name,"",0,type.startsWith("video/")));
            }
        }catch(Exception ignored){}
        for(String dir:dirs){if(out.size()>=MAX_IMAGES)break;walk(c,tree,dir,out,depth+1);}
    }

    @android.annotation.SuppressLint("InlinedApi")
    static List<Item> media(Context c){
        List<Item> items=new ArrayList<>();boolean modern=android.os.Build.VERSION.SDK_INT>=29;
        String location=modern?MediaStore.MediaColumns.RELATIVE_PATH:MediaStore.MediaColumns.DATA;
        Uri files=MediaStore.Files.getContentUri("external");
        try(Cursor rows=c.getContentResolver().query(files,new String[]{MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.MIME_TYPE,location},"media_type IN (1,3)",null,MediaStore.MediaColumns.DISPLAY_NAME+" ASC")){
            while(rows!=null&&rows.moveToNext()){String type=rows.getString(2);if(type==null||!(type.startsWith("image/")||type.startsWith("video/")))continue;String folder=rows.getString(3);if(folder==null)folder="";if(modern)while(folder.startsWith("/"))folder=folder.substring(1);if(!modern){int slash=folder.lastIndexOf('/');folder=slash<0?"":folder.substring(0,slash+1);String storage=android.os.Environment.getExternalStorageDirectory().getAbsolutePath()+"/";if(folder.startsWith(storage))folder=folder.substring(storage.length());}items.add(new Item(ContentUris.withAppendedId(files,rows.getLong(0)),rows.getString(1),folder,0,type.startsWith("video/")));}
        }catch(Exception ignored){}
        return items;
    }

    // ---- pure helpers, covered by plain JVM tests ----

    /** Whether a picked file is a video. */
    static boolean isVideo(String mimeType,String nameOrPath){
        if(mimeType!=null&&mimeType.startsWith("video/"))return true;
        String lower=nameOrPath==null?"":nameOrPath.toLowerCase(java.util.Locale.ROOT);
        return lower.matches(".*\\.(mp4|mkv|webm|m4v|mov|avi|3gp|ts)$");
    }

    static int modeIndexOf(String mode){switch(mode){case "file":return 1;case "folder":return 2;case "builtin":case "app":return 3;case "provider":return 4;default:return 0;}}

    /** Whether a MediaStore item's folder lies in the chosen folder's subtree (trailing slashes ignored). */
    static boolean folderMatches(String chosen,String itemFolder){
        if(chosen==null)return false;if(itemFolder==null)return false;if(chosen.isEmpty())return true;
        String prefix=chosen.endsWith("/")?chosen:chosen+"/";
        return itemFolder.equals(chosen)||itemFolder.startsWith(prefix);
    }
}
