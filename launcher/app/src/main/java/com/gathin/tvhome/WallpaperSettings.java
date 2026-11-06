package com.gathin.tvhome;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;
import java.util.*;

/** Local document selection, with a MediaStore folder chooser on TVs without DocumentsUI. */
final class WallpaperSettings {
    static final int PICK_FILE=710,PICK_FOLDER=711,PICK_APP=712,MEDIA_PERMISSION=713;
    private final LauncherSettingsActivity activity;
    private android.app.WallpaperInfo pendingApp;
    private boolean mediaFile;
    WallpaperSettings(LauncherSettingsActivity activity){this.activity=activity;}
    private void message(String text){Toast.makeText(activity,text,Toast.LENGTH_LONG).show();}
    void file(){activity.startActivityForResult(new Intent(activity,WallpaperBrowserActivity.class),PICK_FILE);}
    void folder(){activity.startActivityForResult(new Intent(activity,WallpaperBrowserActivity.class).putExtra("folder",true),PICK_FOLDER);}
    void mediaReady(){}
    void application(){
        List<android.app.WallpaperInfo> apps=new ArrayList<>();
        for(ResolveInfo info:activity.getPackageManager().queryIntentServices(new Intent("android.service.wallpaper.WallpaperService"),PackageManager.GET_META_DATA))try{apps.add(new android.app.WallpaperInfo(activity,info));}catch(Exception ignored){}
        if(apps.isEmpty()){message("Совместимые живые обои не установлены. Aerial Dream и Aerial Views в режиме заставки не предоставляют фон лаунчеру");return;}
        String[] names=new String[apps.size()];for(int i=0;i<names.length;i++)names[i]=apps.get(i).loadLabel(activity.getPackageManager()).toString();
        SettingsColumns.showChoices(activity,"Приложение обоев",names,0,index->{pendingApp=apps.get(index);try{activity.startActivityForResult(new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,pendingApp.getComponent()),PICK_APP);}catch(RuntimeException e){pendingApp=null;WallpaperSource.setMode(activity,"app");SettingsColumns.refresh(activity);message("Система ТВ не поддерживает выбор обоев — фон берётся из уже назначенных системных живых обоев");}});
    }
    void result(int request,int result,Intent data){
        if(request==PICK_APP){android.app.WallpaperInfo selected=WallpaperManager.getInstance(activity).getWallpaperInfo();if(selected!=null&&pendingApp!=null&&selected.getComponent().equals(pendingApp.getComponent())){WallpaperSource.setMode(activity,"app");SettingsColumns.refresh(activity);}pendingApp=null;return;}
        if(result!=Activity.RESULT_OK||data==null)return;
        if(request==PICK_FOLDER&&data.hasExtra("wallpaper_import_source")&&data.getStringExtra("wallpaper_import_source")!=null){String id=data.getStringExtra("wallpaper_import_source");WallpaperSource.prefs(activity).edit().putString("wallpaper_import_"+id,data.getStringExtra("relative_folder")).putBoolean("wallpaper_source_"+id,true).putString(WallpaperSource.MODE,"builtin").apply();WallpaperSource.changed(activity);SettingsColumns.refresh(activity);return;}
        if(request==PICK_FOLDER&&data.hasExtra("relative_folder")){WallpaperSource.prefs(activity).edit().putString(WallpaperSource.FOLDER,"media:"+data.getStringExtra("relative_folder")).putString(WallpaperSource.MODE,"folder").apply();WallpaperSource.changed(activity);activity.setResult(Activity.RESULT_OK);SettingsColumns.refresh(activity);return;}
        if(data.getData()==null)return;Uri uri=data.getData();
        if(data.getBooleanExtra("internal_media_picker",false)){save(request,uri,data.getStringExtra("media_name"));return;}
        try{activity.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);save(request,uri);}catch(SecurityException transientOnly){
            if(request==PICK_FOLDER){message("Файловый менеджер не предоставил постоянный доступ к папке");return;}
            message("Копирование выбранного файла…");new Thread(()->{try{
                String name="wallpaper";try(android.database.Cursor row=activity.getContentResolver().query(uri,new String[]{"_display_name"},null,null,null)){if(row!=null&&row.moveToFirst())name=row.getString(0);}
                String extension=name.contains(".")?name.substring(name.lastIndexOf('.')).replaceAll("[^.A-Za-z0-9]",""):"";
                java.io.File target=new java.io.File(activity.getFilesDir(),"background-"+java.util.UUID.randomUUID()+extension);
                try(java.io.InputStream in=activity.getContentResolver().openInputStream(uri);java.io.OutputStream out=new java.io.FileOutputStream(target)){byte[] buffer=new byte[65536];int n;long copied=0;while((n=in.read(buffer))!=-1){copied+=n;if(copied>2L*1024*1024*1024||activity.getFilesDir().getUsableSpace()<128L*1024*1024)throw new java.io.IOException("Not enough space");out.write(buffer,0,n);}}
                final String label=name;activity.runOnUiThread(()->save(request,Uri.fromFile(target),label));
            }catch(Exception failure){activity.runOnUiThread(()->message("Не удалось сохранить выбранный файл"));}},"wallpaper-import").start();
        }
    }
    private void save(int request,Uri uri){String label="";try(android.database.Cursor row=activity.getContentResolver().query(uri,new String[]{"_display_name"},null,null,null)){if(row!=null&&row.moveToFirst())label=row.getString(0);}catch(Exception ignored){}save(request,uri,label);}
    private void save(int request,Uri uri,String label){if(request==PICK_FILE)WallpaperSource.prefs(activity).edit().putString("background_file_label",label).apply();String key=request==PICK_FOLDER?WallpaperSource.FOLDER:WallpaperSource.FILE;WallpaperSource.prefs(activity).edit().putString(key,uri.toString()).putString(WallpaperSource.MODE,request==PICK_FOLDER?"folder":"file").apply();WallpaperSource.changed(activity);activity.setResult(Activity.RESULT_OK);SettingsColumns.refresh(activity);message("Обои выбраны");}
}
