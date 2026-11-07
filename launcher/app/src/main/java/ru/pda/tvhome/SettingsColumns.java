package ru.pda.tvhome;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.IntConsumer;

/** Native rendering of the approved two-column settings mock. Existing handlers own the values. */
final class SettingsColumns {
    private final Activity activity;
    private final float density;
    private final LinearLayout navigation, list;
    private final TextView title, breadcrumb;
    private final Map<Integer,View> sources=new HashMap<>();
    /** Раздел «Помощь» — предпоследний; строки его экрана рисует HelpMenu. */
    private static final int HELP=9;
    private final String[] names;
    private final int[][] rows={{R.id.reorderChoice}, {}, {R.id.tileCropChoice,R.id.animationsChoice,R.id.oledShiftChoice,R.id.headerPlateChoice,R.id.plateOpacityChoice,R.id.plateBlurChoice}, {},
        {R.id.dimDelayChoice,R.id.dimRampChoice}, {R.id.clockColorChoice,R.id.clockCornerChoice,R.id.clockSizeChoice,R.id.clockFontChoice,R.id.clockPositionChoice},
        {R.id.assistantChoice,R.id.microphoneChoice}, {R.id.sleepSettingsChoice}, {R.id.weatherCityChoice,R.id.weatherGpsChoice}, {}, {R.id.tvSettingsChoice}};
    private int section;
    private boolean choicesOpen;
    private int choiceGeneration;
    private boolean ready;
    private boolean rendering;
    private int returnRow;
    private boolean animations(){return activity.getSharedPreferences(Config.SETTINGS_NAME,0).getBoolean("animations",true)&&android.animation.ValueAnimator.areAnimatorsEnabled();}
    private void enter(boolean backwards){if(!ready)return;list.animate().cancel();list.setAlpha(animations()?0:1);list.setTranslationX(animations()?dp(backwards?-40:64):0);list.animate().alpha(1).translationX(0).setDuration(animations()?400:0).setInterpolator(new android.view.animation.PathInterpolator(.12f,1f,.4f,1f)).start();}
    private void focusAnimation(View view,boolean focused){view.animate().cancel();view.animate().scaleX(focused?1.05f:1).scaleY(focused?1.05f:1).translationZ(focused?dp(12):0).setDuration(animations()?320:0).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
    private final List<View> categoryButtons=new ArrayList<>();
    static void install(Activity activity){new SettingsColumns(activity);}
    private int dp(float value){return Math.round(value*density);}
    private TextView text(String value,int size,int color){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setTypeface(Typeface.SANS_SERIF);return t;}
    private GradientDrawable fill(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(8));return d;}
    private SettingsColumns(Activity a){
        activity=a;density=a.getResources().getDisplayMetrics().density;
        names=new String[]{"Главный экран","Показывать на экране","Оформление","Обои","Затемнение","Часы поверх приложений","Голосовой помощник","Таймер сна","Погода","Помощь","Система"};
        for(int[] group:rows)for(int id:group)sources.put(id,a.findViewById(id));
        for(int id:new int[]{R.id.columns4,R.id.columns5,R.id.columns6,R.id.columns7,R.id.rows1,R.id.rows2,R.id.rows3})sources.put(id,a.findViewById(id));
        FrameLayout host=a.findViewById(android.R.id.content);ScrollView old=(ScrollView)host.getChildAt(0);
        View originals=old.getChildAt(0);old.removeView(originals);host.removeAllViews();originals.setVisibility(View.GONE);host.addView(originals);
        host.setTag(this);
        LinearLayout root=new LinearLayout(a);root.setBackgroundColor(0xFF12161B);
        navigation=new LinearLayout(a);navigation.setOrientation(LinearLayout.VERTICAL);navigation.setPadding(dp(110),dp(60),dp(112),dp(24));navigation.setBackgroundColor(0xFF0F1318);navigation.setClipChildren(false);navigation.setClipToPadding(false);
        TextView label=text("Настройки лаунчера",32,0xFFE8EAED);label.setPadding(dp(20),0,0,dp(52));navigation.addView(label);
        for(int i=0;i<names.length;i++){
            final int group=i;
            LinearLayout item=new LinearLayout(a);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(20),0,dp(14),0);item.setFocusable(true);item.setClickable(true);item.setClipChildren(false);
            int[] symbols={R.drawable.settings_layout_grid,R.drawable.settings_layout_grid,R.drawable.settings_palette,R.drawable.settings_palette,R.drawable.settings_sun_moon,R.drawable.settings_clock_3,R.drawable.ic_microphone,R.drawable.ic_sleep_moon,R.drawable.settings_cloud_sun,R.drawable.settings_help,R.drawable.ic_settings};
            ImageView icon=new ImageView(a);icon.setImageResource(symbols[i]);icon.setPadding(dp(10),dp(10),dp(10),dp(10));android.graphics.drawable.GradientDrawable circle=fill(0xFF1D242C);circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);icon.setBackground(circle);icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF9AA0A6));item.addView(icon,new LinearLayout.LayoutParams(dp(40),dp(40)));
            TextView button=text(names[i],14,0xFFE8EAED);LinearLayout.LayoutParams textParams=new LinearLayout.LayoutParams(0,-2,1);textParams.leftMargin=dp(12);item.addView(button,textParams);
            item.setOnClickListener(v->{if(group==names.length-1){sources.get(R.id.tvSettingsChoice).performClick();return;}returnRow=0;showGroup(group,true);});
            item.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){if(event.getAction()==KeyEvent.ACTION_DOWN){int next=group+(key==KeyEvent.KEYCODE_DPAD_UP?-1:1);if(next>=0&&next<categoryButtons.size())categoryButtons.get(next).requestFocus();}return true;}if(key==KeyEvent.KEYCODE_DPAD_RIGHT){if(event.getAction()==KeyEvent.ACTION_DOWN){returnRow=0;showGroup(group,true);}return true;}return false;});
            item.setOnFocusChangeListener((v,on)->{item.setBackground(fill(on?0xFFE8EAED:0x00000000));button.setTextColor(on?0xFF0E0E0F:0xFFE8EAED);circle.setColor(on?0xFF263041:0xFF1D242C);icon.setImageTintList(android.content.res.ColorStateList.valueOf(on?0xFF8AB4F8:0xFF9AA0A6));focusAnimation(item,on);if(on&&!rendering){returnRow=0;showGroup(group,false);}});
            LinearLayout.LayoutParams itemParams=new LinearLayout.LayoutParams(-1,dp(60));itemParams.bottomMargin=dp(4);navigation.addView(item,itemParams);categoryButtons.add(item);
        }
        ScrollView navScroll=new ScrollView(a);navScroll.setFillViewport(true);navScroll.setClipChildren(false);navScroll.setClipToPadding(false);navScroll.setVerticalScrollBarEnabled(false);navScroll.addView(navigation);root.addView(navScroll,new LinearLayout.LayoutParams(0,-1,.5854167f));
        LinearLayout pane=new LinearLayout(a);pane.setOrientation(LinearLayout.VERTICAL);pane.setClipChildren(false);pane.setClipToPadding(false);pane.setPadding(dp(52),dp(60),dp(26),dp(24));
        LinearLayout heading=new LinearLayout(a);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView back=text("‹",28,0xFFF1F3F4);back.setGravity(Gravity.CENTER);back.setFocusable(true);back.setClickable(true);back.setOnClickListener(v->{if(choicesOpen)showGroup(section,true);else categoryButtons.get(section).requestFocus();});back.setVisibility(View.GONE);heading.addView(back,new LinearLayout.LayoutParams(0,0));
        title=text("",32,0xFFE8EAED);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));pane.addView(heading);
        breadcrumb=text("Настройки лаунчера",12,0xFF9AA0A6);breadcrumb.setPadding(0,dp(12),0,dp(20));breadcrumb.setVisibility(View.GONE);heading.setPadding(dp(20),0,0,dp(48));pane.addView(breadcrumb);
        // A focused row lifts on translationZ and would slide over the heading when scrolled; an opaque,
        // higher backdrop keeps the caption clean. The outline is off so the elevation draws no shadow.
        heading.setBackgroundColor(0xFF12161B);heading.setElevation(dp(24));heading.setOutlineProvider(null);
        breadcrumb.setBackgroundColor(0xFF12161B);breadcrumb.setElevation(dp(24));breadcrumb.setOutlineProvider(null);
        list=new LinearLayout(a);list.setOrientation(LinearLayout.VERTICAL);list.setClipChildren(false);list.setClipToPadding(false);ScrollView scroller=new ScrollView(a);scroller.setPadding(dp(10),0,dp(10),dp(14));scroller.setClipChildren(true);scroller.setClipToPadding(false);scroller.setVerticalScrollBarEnabled(false);scroller.addView(list);pane.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));root.addView(pane,new LinearLayout.LayoutParams(0,-1,.4145833f));host.addView(root);
        String requested=a.getIntent().getStringExtra("section");int initial="voice".equals(requested)?6:"sleep".equals(requested)?7:0;
        showGroup(initial,false);categoryButtons.get(initial).requestFocus();ready=true;
        if("voice".equals(requested)||"sleep".equals(requested))showGroup(initial,true);
    }
    private View row(String label,String value,boolean enabled,Runnable action,Boolean switched,Boolean checked){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(20),dp(10),dp(20),dp(10));row.setMinimumHeight(dp(60));row.setFocusable(enabled);row.setClickable(enabled);row.setEnabled(enabled);row.setAlpha(enabled?1:.38f);
        final TextView[] indicator=new TextView[1];LinearLayout copy=new LinearLayout(activity);copy.setOrientation(LinearLayout.VERTICAL);TextView name=text(label,14,0xFFE8EAED);copy.addView(name);TextView sub=text(value==null?"":value,12,0xFF9AA0A6);if(value!=null&&!value.isEmpty()){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(3);copy.addView(sub,lp);}row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        if(switched!=null){Switch toggle=new Switch(activity);toggle.setChecked(switched);toggle.setFocusable(false);toggle.setClickable(false);row.addView(toggle);}else{TextView arrow=text(checked==null?"›":checked?"◉":"○",20,0xFFF1F3F4);indicator[0]=arrow;row.addView(arrow,new LinearLayout.LayoutParams(dp(30),-2));}
        LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);rowParams.bottomMargin=dp(4);row.setLayoutParams(rowParams);row.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){if(event.getAction()==KeyEvent.ACTION_DOWN){int step=key==KeyEvent.KEYCODE_DPAD_UP?-1:1;for(int i=list.indexOfChild(row)+step;i>=0&&i<list.getChildCount();i+=step)if(list.getChildAt(i).isFocusable()){list.getChildAt(i).requestFocus();break;}}return true;}if(key==KeyEvent.KEYCODE_DPAD_LEFT){if(event.getAction()==KeyEvent.ACTION_DOWN)categoryButtons.get(section).requestFocus();return true;}return false;});row.setOnClickListener(v->{returnRow=list.indexOfChild(row);action.run();});row.setOnFocusChangeListener((v,on)->{row.setBackground(fill(on?0xFFE8EAED:0x00000000));name.setTextColor(on?0xFF202124:0xFFF1F3F4);sub.setTextColor(on?0xFF68696B:0xFF9AA0A6);if(indicator[0]!=null)indicator[0].setTextColor(on?0xFF0E0E0F:0xFFE8EAED);focusAnimation(row,on);});return row;
    }
    private void showGroup(int group,boolean focus){
        rendering=true;boolean changed=section!=group||choicesOpen;section=group;choicesOpen=false;title.setText(names[group]);breadcrumb.setText("Настройки лаунчера");list.removeAllViews();

        if(group==0){
            int columns=activity.getSharedPreferences(Config.SETTINGS_NAME,0).getInt("columns",4),count=activity.getSharedPreferences(Config.SETTINGS_NAME,0).getInt("visible_rows",2);
            list.addView(row("Приложений в ряду",String.valueOf(columns),true,()->choose("Приложений в ряду",new String[]{"4","5","6","7"},columns-4,index->{sources.get(new int[]{R.id.columns4,R.id.columns5,R.id.columns6,R.id.columns7}[index]).performClick();}),null,null));
            list.addView(row("Рядов приложений",String.valueOf(count),true,()->choose("Рядов приложений",new String[]{"1","2","3"},count-1,index->{sources.get(new int[]{R.id.rows1,R.id.rows2,R.id.rows3}[index]).performClick();}),null,null));
        }
        if(group==3){
            list.addView(row("Источник",WallpaperSource.MODES[WallpaperSource.modeIndex(activity)],true,()->choose("Источник обоев",WallpaperSource.MODES,WallpaperSource.modeIndex(activity),i->{if(i==3)WallpaperSource.setMode(activity,"builtin");else if(i==4){if(ProjectivyClient.discover(activity)==null)Toast.makeText(activity,"Aerial Views с поддержкой Projectivy не найден",Toast.LENGTH_LONG).show();else WallpaperSource.setMode(activity,"provider");}else WallpaperSource.setMode(activity,new String[]{"none","file","folder"}[i]);}),null,null));
            String mode=WallpaperSource.mode(activity);
            if(mode.equals("file"))list.addView(row("Выбрать файл",WallpaperSource.name(activity,WallpaperSource.FILE),true,()->((LauncherSettingsActivity)activity).pickBackgroundFile(),null,null));
            if(mode.equals("folder")){
                list.addView(row("Выбрать папку",WallpaperSource.name(activity,WallpaperSource.FOLDER),true,()->((LauncherSettingsActivity)activity).pickBackgroundFolder(),null,null));
                int minutes=WallpaperSource.minutes(activity),selected=0;for(int i=0;i<WallpaperSource.INTERVALS.length;i++)if(WallpaperSource.INTERVALS[i]==minutes)selected=i;final int current=selected;
                list.addView(row("Менять каждые",minutes+" мин.",true,()->choose("Менять каждые",new String[]{"1 минута","5 минут","15 минут","30 минут"},current,i->{WallpaperSource.prefs(activity).edit().putInt(WallpaperSource.MINUTES,WallpaperSource.INTERVALS[i]).apply();WallpaperSource.changed(activity);}),null,null));
                list.addView(row("Режим смены обоев","Плитки становятся прозрачными",true,()->{WallpaperSource.requestNext(activity);activity.finish();},null,null));
            }
            if(mode.equals("provider"))list.addView(row("Режим смены обоев","Плитки становятся прозрачными",true,()->{WallpaperSource.requestNext(activity);activity.finish();},null,null));
            if(mode.equals("builtin")||mode.equals("app")){
                for(int n=0;n<BuiltinWallpapers.IDS.length;n++){final String id=BuiltinWallpapers.IDS[n];boolean on=BuiltinWallpapers.enabled(activity,id);
                    list.addView(row(BuiltinWallpapers.NAMES[n],BuiltinWallpapers.DETAILS[n],true,()->{WallpaperSource.prefs(activity).edit().putBoolean("wallpaper_source_"+id,!on).apply();
                        if(id.equals("local")&&!on){String permission=android.os.Build.VERSION.SDK_INT>=33?android.Manifest.permission.READ_MEDIA_VIDEO:android.Manifest.permission.READ_EXTERNAL_STORAGE;if(activity.checkSelfPermission(permission)!=android.content.pm.PackageManager.PERMISSION_GRANTED)activity.requestPermissions(new String[]{permission},WallpaperSettings.MEDIA_PERMISSION);}
                        WallpaperSource.changed(activity);showGroup(group,true);},on,null));
                    if(BuiltinWallpapers.IMPORT_ID.equals(id))list.addView(row("Выбрать папку",WallpaperSource.prefs(activity).getString("wallpaper_import_"+id,"Не выбрана"),true,()->activity.startActivityForResult(new android.content.Intent(activity,WallpaperBrowserActivity.class).putExtra("folder",true).putExtra("wallpaper_import_source",id),WallpaperSettings.PICK_FOLDER),null,null));

                }
                list.addView(row("Выбор видеообоев",null,true,()->{WallpaperSource.requestNext(activity);activity.finish();},null,null));
            }
            View transition = row("Плавное появление и смена", "3 секунды", true, () -> {}, null, null);
            // Informational rows still take focus so Down reveals the bottom of the settings list.
            transition.setClickable(false);
            ((android.view.ViewGroup) transition).getChildAt(1).setVisibility(View.INVISIBLE);
            list.addView(transition);
        }
        if(group==2){
            android.content.SharedPreferences prefs=activity.getSharedPreferences(Config.SETTINGS_NAME,0);
            boolean glow=prefs.getBoolean("tile_glow",activity.getResources().getBoolean(R.bool.ambient_glow));
            list.addView(row("Подсветка плиток",glow?"Включена":"Выключена",true,()->{
                android.content.SharedPreferences p=activity.getSharedPreferences(Config.SETTINGS_NAME,0);
                p.edit().putBoolean("tile_glow",!glow).putLong(Config.SETTINGS_REVISION_KEY,p.getLong(Config.SETTINGS_REVISION_KEY,0)+1).apply();
                activity.setResult(Activity.RESULT_OK);showGroup(group,true);
            },glow,null));
            final int brightness=prefs.getInt("tile_glow_brightness",50);
            final int[] options={10,20,30,40,50,60,70,80,90};int current=0;for(int i=0;i<options.length;i++)if(options[i]==brightness)current=i;final int cur=current;
            list.addView(row("Яркость подсветки",brightness+"%",true,()->choose("Яркость подсветки",new String[]{"10%","20%","30%","40%","50%","60%","70%","80%","90%"},cur,i->{
                android.content.SharedPreferences p=activity.getSharedPreferences(Config.SETTINGS_NAME,0);
                p.edit().putInt("tile_glow_brightness",options[i]).putLong(Config.SETTINGS_REVISION_KEY,p.getLong(Config.SETTINGS_REVISION_KEY,0)+1).apply();
                activity.setResult(Activity.RESULT_OK);
            }),null,null));
        }
        if(group==1){
            android.content.pm.PackageManager pm=activity.getPackageManager();
            Map<String,android.content.pm.ResolveInfo> apps=new TreeMap<>();Set<String> tv=new HashSet<>();
            for(String category:new String[]{android.content.Intent.CATEGORY_LEANBACK_LAUNCHER,android.content.Intent.CATEGORY_LAUNCHER})
                for(android.content.pm.ResolveInfo info:pm.queryIntentActivities(new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(category),0))
                    if(info.activityInfo!=null&&info.activityInfo.exported&&!info.activityInfo.packageName.equals(activity.getPackageName())){apps.put(info.activityInfo.packageName,info);if(category.equals(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER))tv.add(info.activityInfo.packageName);}
            List<String> packages=new ArrayList<>(apps.keySet());packages.sort((a,b)->VpnCatalog.label(activity,a).compareToIgnoreCase(VpnCatalog.label(activity,b)));
            Set<String> added=LibraryApps.load(activity),removed=LibraryApps.loadRemoved(activity);Config config=Config.load(activity);
            for(String pkg:packages){boolean visible=LibraryApps.visible(tv.contains(pkg),config.hiddenApps.contains(pkg),added.contains(pkg)||config.libraryApps.contains(pkg),removed.contains(pkg));
                list.addView(row(VpnCatalog.label(activity,pkg),null,true,()->{if(LibraryApps.save(activity,pkg,!visible)){
                    android.content.SharedPreferences prefs=activity.getSharedPreferences(Config.SETTINGS_NAME,0);
                    prefs.edit().putLong(Config.SETTINGS_REVISION_KEY,prefs.getLong(Config.SETTINGS_REVISION_KEY,0)+1).apply();activity.setResult(Activity.RESULT_OK);showGroup(group,true);
                }},visible,null));
            }
        }
        if(group==HELP)HelpMenu.install(activity,list,density,()->categoryButtons.get(HELP).requestFocus());
        for(int id:rows[group]){
            View original=sources.get(id);if(!(original instanceof TextView))continue;
            if(group==5&&id!=R.id.clockColorChoice&&!activity.getSharedPreferences(Config.SETTINGS_NAME,0).getBoolean(Config.CLOCK_ON_KEY,false))continue;
            if(group==2&&(id==R.id.plateOpacityChoice||id==R.id.plateBlurChoice)&&!activity.getSharedPreferences(Config.SETTINGS_NAME,0).getBoolean(Config.HEADER_PLATE_KEY,activity.getResources().getBoolean(R.bool.header_plate)))continue;
            if(id==R.id.reorderChoice&&original.getVisibility()==View.GONE)continue;
            String full=((TextView)original).getText().toString();int colon=full.indexOf(':');String label=colon<0?full:full.substring(0,colon),value=colon<0?"":full.substring(colon+1).trim();
            boolean binary=id==R.id.animationsChoice||id==R.id.oledShiftChoice||id==R.id.headerPlateChoice||id==R.id.clockColorChoice||id==R.id.assistantChoice||id==R.id.microphoneChoice;
            Boolean on=null;if(binary){android.content.SharedPreferences prefs=activity.getSharedPreferences(Config.SETTINGS_NAME,0);on=id==R.id.assistantChoice?AssistantChoice.isLauncher(activity):id==R.id.microphoneChoice?prefs.getInt("voice_microphone",1)==1:id==R.id.animationsChoice?prefs.getBoolean("animations",true):id==R.id.clockColorChoice?prefs.getBoolean(Config.CLOCK_ON_KEY,false):id==R.id.headerPlateChoice?prefs.getBoolean(Config.HEADER_PLATE_KEY,activity.getResources().getBoolean(R.bool.header_plate)):prefs.getBoolean(Config.OLED_SHIFT_KEY,false);}
            list.addView(row(label,value,original.isEnabled(),()->{original.performClick();if(!choicesOpen)showGroup(group,true);},on,null));
        }
        if(focus){if(returnRow<list.getChildCount()&&list.getChildAt(returnRow).isFocusable())list.getChildAt(returnRow).requestFocus();else focusFirst();}rendering=false;if(changed)enter(false);
    }
    private void focusFirst(){for(int i=0;i<list.getChildCount();i++)if(list.getChildAt(i).isFocusable()){list.getChildAt(i).requestFocus();break;}}
    private void choose(String heading,String[] labels,int current,IntConsumer selected){final int origin=returnRow;final int choice=++choiceGeneration;rendering=true;choicesOpen=true;title.setText(heading);breadcrumb.setText("Настройки лаунчера · "+names[section]);list.removeAllViews();for(int i=0;i<labels.length;i++){final int index=i;list.addView(row(labels[i],null,true,()->{returnRow=origin;selected.accept(index);if(choice==choiceGeneration)showGroup(section,true);},null,i==current));}if(current>=0&&current<list.getChildCount())list.getChildAt(current).requestFocus();else focusFirst();rendering=false;enter(false);}
    static boolean showChoices(Activity a,String title,String[] labels,int selected,IntConsumer callback){Object tag=((View)a.findViewById(android.R.id.content)).getTag();if(!(tag instanceof SettingsColumns))return false;((SettingsColumns)tag).choose(title,labels,selected,callback);return true;}
    static void refresh(Activity a){Object tag=((View)a.findViewById(android.R.id.content)).getTag();if(tag instanceof SettingsColumns){SettingsColumns ui=(SettingsColumns)tag;if(!ui.choicesOpen&&!ui.rendering){boolean focused=ui.list.hasFocus();if(focused){for(int i=0;i<ui.list.getChildCount();i++)if(ui.list.getChildAt(i).hasFocus()){ui.returnRow=i;break;}}ui.showGroup(ui.section,focused);}}}
    static boolean back(Activity a){Object tag=((View)a.findViewById(android.R.id.content)).getTag();if(!(tag instanceof SettingsColumns))return false;SettingsColumns ui=(SettingsColumns)tag;if(ui.choicesOpen){ui.showGroup(ui.section,true);ui.enter(true);return true;}if(!ui.navigation.hasFocus()){ui.categoryButtons.get(ui.section).requestFocus();return true;}return false;}
}
