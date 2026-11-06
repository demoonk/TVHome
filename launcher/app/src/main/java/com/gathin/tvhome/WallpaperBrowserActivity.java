package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Remote-friendly browser for local images/video, without launching an external file manager. */
public final class WallpaperBrowserActivity extends Activity {
    private LinearLayout rows;
    private TextView path,choose,back;
    private String directory="";
    private boolean folders;
    private List<WallpaperSource.Item> items=Collections.emptyList();
    private float density;
    private int dp(int value){return Math.round(value*density);}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(0xFFE8EAED);return t;}
    private GradientDrawable fill(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(8));return d;}
    private TextView button(String value,Runnable action){TextView b=text(value,16);b.setGravity(Gravity.CENTER_VERTICAL);b.setPadding(dp(20),dp(14),dp(20),dp(14));b.setMinHeight(dp(56));b.setFocusable(true);b.setClickable(true);b.setBackground(fill(0xFF303744));b.setOnClickListener(v->action.run());b.setOnFocusChangeListener((v,on)->{b.setBackground(fill(on?0xFFE8EAED:0xFF303744));b.setTextColor(on?0xFF15191F:0xFFE8EAED);});return b;}
    @Override public void onCreate(Bundle state){super.onCreate(state);folders=getIntent().getBooleanExtra("folder",false);directory=state==null?"":state.getString("directory","");density=getResources().getDisplayMetrics().density;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(64),dp(32),dp(64),dp(24));root.setBackgroundColor(0xFF1E232C);
        root.addView(text(folders?"Выберите папку":"Выберите файл",28));path=text("/sdcard/",14);path.setPadding(0,dp(12),0,dp(20));root.addView(path);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        back=button("Назад",this::onBackPressed);actions.addView(back,new LinearLayout.LayoutParams(0,-2,1));
        choose=button("Выбрать эту папку",()->{Intent result=new Intent().putExtra("wallpaper_import_source",getIntent().getStringExtra("wallpaper_import_source")).putExtra("relative_folder",directory).putExtra("internal_media_picker",true);setResult(RESULT_OK,result);finish();});
        LinearLayout.LayoutParams selected=new LinearLayout.LayoutParams(0,-2,2);selected.leftMargin=dp(16);actions.addView(choose,selected);choose.setVisibility(folders?View.VISIBLE:View.GONE);root.addView(actions);
        ScrollView scroll=new ScrollView(this);scroll.setPadding(0,dp(16),0,0);rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView hint=text("Локальная медиатека ТВ · Изображения и видео",12);hint.setTextColor(0xFFAEB7C5);root.addView(hint);setContentView(root);permissions();
    }
    private void permissions(){String[] permissions=Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO}:new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};boolean granted=true;for(String p:permissions)granted&=checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;if(granted)load();else requestPermissions(permissions,1);}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] granted){super.onRequestPermissionsResult(request,permissions,granted);boolean any=false;for(int result:granted)any|=result==PackageManager.PERMISSION_GRANTED;if(any)load();else{rows.removeAllViews();rows.addView(text("Нет доступа к изображениям и видео. Разрешите доступ в настройках приложения",16));choose.setEnabled(false);}}
    private void load(){choose.setEnabled(false);rows.removeAllViews();rows.addView(text("Загрузка…",16));new Thread(()->{List<WallpaperSource.Item> found=WallpaperSource.media(this);runOnUiThread(()->{if(isFinishing()||isDestroyed())return;items=found;choose.setEnabled(true);render();});},"wallpaper-browser").start();}
    private void add(TextView row){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(6);rows.addView(row,params);row.setOnKeyListener((v,key,event)->{if(key!=KeyEvent.KEYCODE_DPAD_UP&&key!=KeyEvent.KEYCODE_DPAD_DOWN)return false;if(event.getAction()==KeyEvent.ACTION_DOWN){int step=key==KeyEvent.KEYCODE_DPAD_UP?-1:1,next=rows.indexOfChild(row)+step;if(next>=0&&next<rows.getChildCount())rows.getChildAt(next).requestFocus();else if(next<0)(folders?choose:back).requestFocus();}return true;});}
    private void render(){path.setText("/sdcard/"+directory);rows.removeAllViews();Set<String> children=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);List<WallpaperSource.Item> files=new ArrayList<>();
        for(WallpaperSource.Item item:items){if(!item.folder.startsWith(directory))continue;String rest=item.folder.substring(directory.length());int slash=rest.indexOf('/');if(!rest.isEmpty()){String child=slash<0?rest:rest.substring(0,slash);if(!child.isEmpty())children.add(child);}else files.add(item);}
        for(String child:children)add(button("▸  "+child,()->{directory+=child+"/";render();}));
        files.sort(Comparator.comparing(i->i.name,String.CASE_INSENSITIVE_ORDER));
        if(!folders)for(WallpaperSource.Item file:files)add(button("▣  "+file.name,()->{setResult(RESULT_OK,new Intent().setData(file.uri).putExtra("internal_media_picker",true).putExtra("media_name",file.name));finish();}));
        if(children.isEmpty()&&(folders||files.isEmpty()))rows.addView(text(folders?"В этой папке нет вложенных папок. Можно выбрать её целиком":"Здесь нет доступных изображений или видео",16));
        if(rows.getChildCount()>0&&rows.getChildAt(0).isFocusable())rows.getChildAt(0).requestFocus();else if(folders)choose.requestFocus();
    }
    @Override public void onBackPressed(){if(directory.isEmpty()){super.onBackPressed();return;}String parent=directory.substring(0,directory.length()-1);int slash=parent.lastIndexOf('/');directory=slash<0?"":parent.substring(0,slash+1);render();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("directory",directory);}
}
