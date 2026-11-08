package ru.pda.tvhome;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Public website results; never depends on the installed app's private provider. */
final class SiteSearch {
    static final int REQUEST=730;
    static boolean supports(MediaVoiceApps.App app){return "vk".equals(app.id)||"rutube".equals(app.id)||"kinopoisk".equals(app.id);}
    static String url(String id,String query){
        query=query==null?"":query;
        return "vk".equals(id)?"https://vkvideo.ru/results?q="+Uri.encode(query):"rutube".equals(id)?"https://rutube.ru/search/?query="+Uri.encode(query):"https://www.kinopoisk.ru/index.php?kp_query="+Uri.encode(query);
    }
    static boolean content(String id,Uri u){
        return SiteSearchRules.content(id,u.toString());
    }
    static List<MediaSearch.Hit> find(Context c,MediaVoiceApps.App app,String query,CancellationSignal signal){
        try{
            if("rutube".equals(app.id)){
                JSONArray rows=new JSONObject(WebData.get("https://rutube.ru/api/search/video/?query="+Uri.encode(query)+"&page=1",signal)).getJSONArray("results");
                List<MediaSearch.Hit> hits=new ArrayList<>();
                for(int i=0;i<rows.length()&&hits.size()<20;i++){JSONObject r=rows.getJSONObject(i);String link=r.optString("video_url","https://rutube.ru/video/"+r.optString("id")+"/");if(!content(app.id,Uri.parse(link)))continue;hits.add(hit(app,r.optString("title"),r.optString("thumbnail_url"),link));}return hits;
            }
            if(!(c instanceof Activity))throw new IllegalStateException();
            Activity a=(Activity)c;Handler main=new Handler(Looper.getMainLooper());CountDownLatch finished=new CountDownLatch(1);
            List<MediaSearch.Hit> hits=new ArrayList<>();WebView[] browser={null};
            main.post(()->{
                if(signal.isCanceled()||a.isDestroyed()||a.isFinishing()){finished.countDown();return;}
                try{
                    WebView web=new WebView(a);browser[0]=web;configure(web);
                    web.setAlpha(.01f);web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);web.setFocusable(false);
                    ((ViewGroup)a.findViewById(android.R.id.content)).addView(web,new ViewGroup.LayoutParams(1280,720));
                    web.setTranslationX(-10000);
                    web.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView w,String u){main.postDelayed(()->extract(w,app,hits,finished,signal),1500);}});
                    web.loadUrl(url(app.id,query));
                }catch(RuntimeException unavailable){finished.countDown();}
            });
            try{for(int i=0;i<28;i++){signal.throwIfCanceled();if(finished.await(500,TimeUnit.MILLISECONDS))break;}}finally{main.post(()->{finished.countDown();WebView w=browser[0];if(w!=null){w.stopLoading();if(w.getParent()!=null)((ViewGroup)w.getParent()).removeView(w);w.destroy();}});}
            signal.throwIfCanceled();synchronized(hits){return new ArrayList<>(hits);}
        }catch(Exception failure){signal.throwIfCanceled();throw new IllegalStateException("Website search unavailable",failure);}
    }
    private static void extract(WebView web,MediaVoiceApps.App app,List<MediaSearch.Hit> hits,CountDownLatch done,CancellationSignal signal){
        if(signal.isCanceled()||done.getCount()==0)return;
        try{web.evaluateJavascript("JSON.stringify(Array.from(document.querySelectorAll('a[href]')).map(a=>({url:a.href,title:a.getAttribute('title')||a.getAttribute('aria-label')||a.innerText||(a.querySelector('img')||{}).alt,img:(a.querySelector('img')||{}).src||''})).filter(a=>a.title).slice(0,1500))",value->{
            if(signal.isCanceled()||done.getCount()==0)return;
            try{Object decoded=new JSONTokener(value).nextValue();JSONArray rows=new JSONArray((String)decoded);Set<String> seen=new HashSet<>();synchronized(hits){
                for(int i=0;i<rows.length()&&hits.size()<20;i++){JSONObject row=rows.getJSONObject(i);String link=row.optString("url");if(content(app.id,Uri.parse(link))&&seen.add(link)){String title=row.optString("title").trim();if(!title.isEmpty())hits.add(hit(app,title,row.optString("img"),link));}}
            }}catch(Exception ignored){}
            synchronized(hits){if(!hits.isEmpty()){done.countDown();return;}}
            // Client-rendered results may arrive after onPageFinished or a login redirect.
            web.postDelayed(()->extract(web,app,hits,done,signal),750);
        });}catch(RuntimeException unavailable){done.countDown();}
    }
    static void configure(WebView web){web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);}
    private static MediaSearch.Hit hit(MediaVoiceApps.App app,String title,String poster,String url){return new MediaSearch.Hit(title,app.label,poster,new Intent(Intent.ACTION_VIEW,Uri.parse(SiteSearchRules.appLink(app.id,url))).setPackage(app.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    static void open(Activity a,MediaVoiceApps.App app,String query){a.startActivityForResult(new Intent(a,SiteSearchActivity.class).putExtra("source",app.id).putExtra("query",query),REQUEST);}
    private SiteSearch(){}
}
