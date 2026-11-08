package ru.pda.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;

/** Visible website fallback for login/browser challenges and client-rendered results. */
public final class SiteSearchActivity extends Activity {
    private WebView web;
    private MediaVoiceApps.App source;
    private boolean returnFromContent;
    private boolean opening,active;
    private long userActionUntil;
    private final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean openContent(MediaVoiceApps.App app,android.net.Uri uri){
        if(!SiteSearch.content(app.id,uri))return false;
        if(!active||isFinishing()||isDestroyed())return true;
        if(opening)return true;
        Intent intent=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(SiteSearchRules.appLink(app.id,uri.toString()))).setPackage(app.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if(intent.resolveActivity(getPackageManager())==null){Toast.makeText(this,"Приложение не поддерживает эту ссылку",Toast.LENGTH_LONG).show();return true;}
        opening=true;
        try{startActivity(intent);setResult(RESULT_OK);finish();overridePendingTransition(0,0);}
        catch(RuntimeException unavailable){opening=false;android.util.Log.w("SearchLaunch","Could not open content in "+app.pkg,unavailable);Toast.makeText(this,"Не удалось открыть в "+app.label,Toast.LENGTH_LONG).show();}
        return true;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);returnFromContent=state!=null&&state.getBoolean("return_from_content",false);MediaVoiceApps.App app=MediaVoiceApps.byId(getIntent().getStringExtra("source"));
        if(app==null||!SiteSearch.supports(app)){finish();return;}source=app;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        TextView hint=new TextView(this);hint.setText(app.label+" · Выберите карточку — она откроется в приложении");hint.setTextSize(18);root.addView(hint);
        web=new WebView(this);SiteSearch.configure(web);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest r){
            if(!r.isForMainFrame())return false;
            if((r.hasGesture()||android.os.SystemClock.uptimeMillis()<userActionUntil)&&openContent(app,r.getUrl())){userActionUntil=0;return true;}
            return !"https".equals(r.getUrl().getScheme());
        }
        @Override public void doUpdateVisitedHistory(WebView view,String url,boolean reload){
            // SPA cards can change history without a navigation request.
            if(!reload&&android.os.SystemClock.uptimeMillis()<userActionUntil&&openContent(app,android.net.Uri.parse(url)))userActionUntil=0;
        }});
        web.setOnKeyListener((v,key,event)->{
            if(event.getAction()==android.view.KeyEvent.ACTION_DOWN&&(key==android.view.KeyEvent.KEYCODE_DPAD_CENTER||key==android.view.KeyEvent.KEYCODE_ENTER||key==android.view.KeyEvent.KEYCODE_NUMPAD_ENTER))userActionUntil=android.os.SystemClock.uptimeMillis()+2000;
            return false;
        });
        web.setOnTouchListener((v,event)->{if(event.getAction()==android.view.MotionEvent.ACTION_UP)userActionUntil=android.os.SystemClock.uptimeMillis()+2000;return false;});
        if(state==null||web.restoreState(state)==null)web.loadUrl(SiteSearch.url(app.id,getIntent().getStringExtra("query")));web.requestFocus();
    }
    @Override protected void onPause(){active=false;userActionUntil=0;if(web!=null)web.onPause();super.onPause();}
    @Override protected void onResume(){super.onResume();active=true;opening=false;if(web!=null){web.onResume();
        if(returnFromContent&&source!=null&&SiteSearch.content(source.id,android.net.Uri.parse(web.getUrl()==null?"":web.getUrl()))){if(web.canGoBack())web.goBack();else web.loadUrl(SiteSearch.url(source.id,getIntent().getStringExtra("query")));}
        }returnFromContent=false;}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putBoolean("return_from_content",returnFromContent);if(web!=null)web.saveState(state);}
    @Override public void onBackPressed(){userActionUntil=0;if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);if(web!=null){web.stopLoading();if(web.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)web.getParent()).removeView(web);web.destroy();web=null;}super.onDestroy();}
}
