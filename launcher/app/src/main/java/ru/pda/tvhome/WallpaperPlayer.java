package ru.pda.tvhome;
import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.media.MediaPlayer;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper;

/**
 * Two layers, each an image or — for the plugin source — one silent looping video.
 * Every first appearance and crossfade lasts exactly three seconds.
 */
final class WallpaperPlayer {
 private final Context context; private final FrameLayout root,background;
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Runnable tick=this::update;
 private ImageView previewView;private boolean previewRequested;
 private Layer current,outgoing,pending; private boolean active,closed,loading,blocked;private int generation,index=-1;private long shownAt;
 /** How many plugin videos in a row failed to prepare. Past this many the player falls silent rather
  *  than burning another timeout per clip forever (a network that black-holes the CDN, say). */
 private int videoSkips;
 /** A plugin video that has not reached "prepared" this long after prepareAsync is treated as hung:
  *  MediaPlayer itself never times out, and one stuck clip must not mean a permanent black background. */
 private static final long PREPARE_TIMEOUT_MS=25000;
 WallpaperPlayer(Context c,FrameLayout root,Config ignored){
  context=c;this.root=root;background=new FrameLayout(c);background.setTag("header_wallpaper_source");background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);root.addView(background,0,new FrameLayout.LayoutParams(-1,-1));
  restorePreview();
  if(c instanceof Activity)((Activity)c).getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
 }
 void start(){start(false);}
 void start(boolean advanceFirst){
  if(closed)return;active=true;blocked=false;
  if(current==null){restorePreview();load(advanceFirst);}
  else if(advanceFirst){current.view.animate().cancel();current.view.setAlpha(1f);load(true);}
  else{current.view.animate().cancel();current.view.setAlpha(1f);current.resumeMotion();schedule();resumeVideo();}
 }
 void stop(){
  active=false;handler.removeCallbacks(tick);generation++;loading=false;
  if(pending!=null){pending.release();pending=null;}
  if(current!=null){
   current.view.animate().cancel();current.view.setAlpha(1f);current.pauseMotion();current.pauseVideo();
   if(current instanceof VideoLayer){Bitmap frame=((VideoLayer)current).snapshot();if(frame!=null){HomeArtwork.remember(context,frame);displayPreview(frame);}current.release();current=null;}
  }
  if(outgoing!=null){outgoing.release();outgoing=null;}
 }
 void trim(){stop();if(current!=null){current.release();current=null;}removePreview();previewRequested=false;}
 void close(){trim();closed=true;worker.shutdownNow();root.removeView(background);}
 private void restorePreview(){
  if(previewRequested||WallpaperSource.mode(context).equals("none"))return;previewRequested=true;
  HomeArtwork.loadPreview(context,bitmap->{if(!closed&&current==null)displayPreview(bitmap);});
 }
 private void displayPreview(Bitmap bitmap){
  if(previewView==null){previewView=new ImageView(context);previewView.setFocusable(false);previewView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);previewView.setScaleType(ImageView.ScaleType.CENTER_CROP);background.addView(previewView,0,new FrameLayout.LayoutParams(-1,-1));}
  previewView.setImageBitmap(bitmap);
 }
 private void removePreview(){if(previewView!=null){background.removeView(previewView);previewView.setImageDrawable(null);previewView=null;}}
 void update(){if(!active||closed||loading)return;if(current==null){load(false);return;}if(!WallpaperSource.mode(context).equals("file")&&SystemClock.elapsedRealtime()-shownAt>=WallpaperSource.minutes(context)*60000L)load(true);}
 /** The wallpaper settings' "Режим смены обоев": show the next picture of the collection now. */
 void next(){if(closed||loading)return;load(1);}
 /** The same, one picture back (the remote's backward controls in the switch preview). */
 void previous(){if(closed||loading)return;load(-1);}
 private void resumeVideo(){if(current!=null)current.resumeVideo();if(pending!=null)pending.resumeVideo();}
 private void schedule(){handler.removeCallbacks(tick);if(active&&!WallpaperSource.mode(context).equals("file"))handler.postDelayed(tick,Math.max(1000,WallpaperSource.minutes(context)*60000L-(SystemClock.elapsedRealtime()-shownAt)));}
 private void load(boolean advance){load(advance?1:0);}
 private void load(int step){
  String mode=WallpaperSource.mode(context);if(!active||closed||loading||mode.equals("none")||blocked)return;
  loading=true;int request=++generation;
  worker.execute(()->{try{
   List<WallpaperSource.Item> items=WallpaperSource.collection(context);if(items.isEmpty()){handler.post(()->{if(request!=generation||closed||!active)return;if(mode.equals("provider")){loading=false;Log.i(ProjectivyClient.TAG,"provider offered no wallpapers");}else failed(request,"В выбранном источнике нет доступных обоев");});return;}
   int n=items.size();
   int next=step>0?(index+1+n)%n:step<0?(index<0?n-1:(index-1+n)%n):Math.max(0,Math.min(index,n-1));
   WallpaperSource.Item item=items.get(next);
   if(item.video){handler.post(()->{if(request!=generation||closed||!active)return;Layer layer=new VideoLayer(item);pending=layer;layer.ready(()->{if(request!=generation||!active||closed){layer.release();return;}pending=null;loading=false;videoSkips=0;show(layer);index=next;shownAt=SystemClock.elapsedRealtime();schedule();},()->{layer.release();if(request!=generation||closed)return;pending=null;loading=false;if(!closed){Log.i(ProjectivyClient.TAG,"video wallpaper skipped: "+item.uri);if(videoSkips<3){videoSkips++;index=next;load(1);}else failed(request,"Не удалось воспроизвести видеообои. Выберите другой источник");}});});return;}
 Bitmap bitmap=decode(item);Bitmap preview=null;try{preview=HomeArtwork.previewCopy(bitmap);}catch(OutOfMemoryError unavailable){Log.d("HomeArtwork","Preview skipped");}final Bitmap savedPreview=preview;
   handler.post(()->{if(request!=generation||closed||!active){if(bitmap!=null)bitmap.recycle();return;}if(savedPreview!=null)HomeArtwork.remember(context,savedPreview);Layer layer=new Layer(item,bitmap);pending=layer;layer.ready(()->{if(request!=generation||!active||closed){layer.release();return;}pending=null;loading=false;show(layer);index=next;shownAt=SystemClock.elapsedRealtime();schedule();},()->{layer.release();pending=null;failed(request,"Не удалось открыть выбранные обои");});});
  }catch(Exception|OutOfMemoryError failure){handler.post(()->failed(request,"Не удалось прочитать обои. Выберите другой файл"));}});
 }
 private void failed(int request,String message){if(request!=generation||closed)return;loading=false;blocked=true;Toast.makeText(context,message,Toast.LENGTH_LONG).show();handler.removeCallbacks(tick);}
 private Bitmap decode(WallpaperSource.Item item)throws Exception{
  BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
  try(java.io.InputStream in=context.getContentResolver().openInputStream(item.uri)){BitmapFactory.decodeStream(in,null,options);}
  if(options.outWidth<1||options.outHeight<1)throw new IllegalArgumentException();
  int w=context.getResources().getDisplayMetrics().widthPixels,h=context.getResources().getDisplayMetrics().heightPixels;
  options.inSampleSize=WallpaperSize.sampleSize(options.outWidth,options.outHeight,w,h);options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
  try(java.io.InputStream in=context.getContentResolver().openInputStream(item.uri)){Bitmap image=BitmapFactory.decodeStream(in,null,options);if(image==null)throw new IllegalArgumentException();return image;}
 }
 private void show(Layer incoming){
  if(outgoing!=null){outgoing.release();outgoing=null;}outgoing=current;current=incoming;
  if(Arrays.asList("aerial","nasa","commons").contains(incoming.item.folder)){
   TextView attribution=new TextView(context);attribution.setText(incoming.item.name);attribution.setTextSize(10);attribution.setTextColor(0xffdddddd);attribution.setBackgroundColor(0x88000000);attribution.setPadding(12,4,12,4);attribution.setMaxLines(4);attribution.setFocusable(false);
   FrameLayout.LayoutParams caption=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);caption.setMargins(24,0,24,12);incoming.view.addView(attribution,caption);
  }
  boolean cached=previewView!=null;
  incoming.view.setAlpha(outgoing==null&&!cached?1f:0f);if(incoming.view.getParent()==null)background.addView(incoming.view,new FrameLayout.LayoutParams(-1,-1));else incoming.view.bringToFront();
  incoming.view.animate().alpha(1).setDuration(outgoing!=null?WallpaperSource.TRANSITION_MS:cached?250:0).setInterpolator(new android.view.animation.LinearInterpolator()).withEndAction(()->{if(current!=incoming)return;if(outgoing!=null){outgoing.release();outgoing=null;}removePreview();}).start();
 }
 private class Layer {
  android.animation.ValueAnimator motion;
  final FrameLayout view=new FrameLayout(context);final WallpaperSource.Item item;final Bitmap bitmap;boolean released;
  Layer(WallpaperSource.Item item,Bitmap bitmap){this.item=item;this.bitmap=bitmap;view.setFocusable(false);}
  void ready(Runnable done,Runnable error){
   if(released){error.run();return;}
   ImageView image=new ImageView(context);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageBitmap(bitmap);view.addView(image,new FrameLayout.LayoutParams(-1,-1));
   int effect=WallpaperSource.animation(context);
   if(effect!=0&&android.animation.ValueAnimator.areAnimatorsEnabled()){motion=android.animation.ValueAnimator.ofFloat(0f,1f);motion.setDuration(WallpaperSource.animationSeconds(context)*1000L);motion.setRepeatCount(android.animation.ValueAnimator.INFINITE);motion.setRepeatMode(android.animation.ValueAnimator.REVERSE);motion.setInterpolator(new android.view.animation.LinearInterpolator());
    motion.addUpdateListener(a->{float t=(Float)a.getAnimatedValue();if(effect==1){image.setScaleX(1.10f);image.setScaleY(1.10f);image.setTranslationX((t*2-1)*view.getWidth()*.045f);}else{float scale=1f+.12f*t;image.setScaleX(scale);image.setScaleY(scale);}});motion.start();}
   done.run();
  }
  void release(){if(released)return;released=true;if(motion!=null){motion.cancel();motion=null;}view.animate().cancel();background.removeView(view);if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
  void pauseMotion(){if(motion!=null)motion.pause();}
  void resumeMotion(){if(motion!=null&&motion.isPaused())motion.resume();}
  void pauseVideo(){}
  void resumeVideo(){}
 }
 /** A video wallpaper from the Projectivy plugin: silent, looping, scaled to the screen like a
  *  picture. Nothing is announced when a clip fails — a third-party plugin must not interrupt Home. */
 private final class VideoLayer extends Layer implements TextureView.SurfaceTextureListener{
  private final TextureView surface=new TextureView(context);private MediaPlayer player;private boolean surfaceReady,prepared,preparing;private Runnable prepareTimeout;private Runnable firstFrame;private Surface attachedSurface;
  VideoLayer(WallpaperSource.Item item){super(item,null);surface.setFocusable(false);view.addView(surface,new FrameLayout.LayoutParams(-1,-1));surface.setSurfaceTextureListener(this);}
  @Override void ready(Runnable done,Runnable error){
   if(released){error.run();return;}
   try{
    MediaPlayer m=new MediaPlayer();player=m;m.setVolume(0f,0f);m.setLooping(true);
    m.setOnErrorListener((mp,what,extra)->{Log.i(ProjectivyClient.TAG,"video error "+what+"/"+extra+" on "+item.uri);if(mp==player){player=null;try{mp.release();}catch(RuntimeException ignored){}}error.run();return true;});
    firstFrame=done;
    m.setOnInfoListener((mp,what,extra)->{if(what==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START&&firstFrame!=null){Runnable ready=firstFrame;firstFrame=null;if(prepareTimeout!=null)handler.removeCallbacks(prepareTimeout);ready.run();}return false;});
    m.setOnPreparedListener(mp->{if(released||mp!=player)return;prepared=true;fit();play();});
    m.setOnCompletionListener(mp->{if(mp==player)try{mp.seekTo(0);mp.start();}catch(RuntimeException ignored){}});
    m.setDataSource(context,item.uri);player=m;if(!preparing){preparing=true;m.prepareAsync();prepareTimeout=()->{if(released||player!=m||firstFrame==null)return;Log.i(ProjectivyClient.TAG,"video prepare timed out on "+item.uri);player=null;try{m.release();}catch(RuntimeException ignored){}error.run();};handler.postDelayed(prepareTimeout,PREPARE_TIMEOUT_MS);}
    if(surface.isAvailable())attach(surface.getSurfaceTexture());
    if(view.getParent()==null){view.setAlpha(0);background.addView(view,new FrameLayout.LayoutParams(-1,-1));}
   }catch(Exception failure){Log.i(ProjectivyClient.TAG,"video refused: "+failure+" on "+item.uri);error.run();}
  }
  private void attach(android.graphics.SurfaceTexture texture){
   if(released||player==null||texture==null)return;
   try{if(attachedSurface!=null)attachedSurface.release();attachedSurface=new Surface(texture);player.setSurface(attachedSurface);surfaceReady=true;fit();play();}catch(RuntimeException failure){Log.i(ProjectivyClient.TAG,"video surface failed: "+failure+" on "+item.uri);}
  }
  private void play(){
   if(released||!active||player==null||!prepared||!surfaceReady)return;
   try{player.start();}catch(RuntimeException ignored){}
  }
  private void fit(){
   if(player==null)return;int vw=surface.getWidth(),vh=surface.getHeight(),cw=player.getVideoWidth(),ch=player.getVideoHeight();if(vw<1||vh<1||cw<1||ch<1)return;
   Matrix m=new Matrix();
   if(item.displayMode==Wallpaper.DISPLAY_STRETCH)m.reset();
   else{boolean inside=item.displayMode==Wallpaper.DISPLAY_FIT_CENTER||item.displayMode==Wallpaper.DISPLAY_FIT_CENTER_BLURRED_BG;float s=inside?Math.min((float)vw/cw,(float)vh/ch):Math.max((float)vw/cw,(float)vh/ch);m.setScale(cw*s/vw,ch*s/vh,vw/2f,vh/2f);}
   surface.setTransform(m);
  }
  @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture texture,int w,int h){surfaceReady=true;attach(texture);}
  @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture texture,int w,int h){fit();}
  @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture texture){surfaceReady=false;if(player!=null)try{player.setSurface(null);}catch(RuntimeException ignored){}if(attachedSurface!=null){attachedSurface.release();attachedSurface=null;}return true;}
  @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture texture){}
  Bitmap snapshot(){try{return surface.isAvailable()?surface.getBitmap(1280,720):null;}catch(RuntimeException|OutOfMemoryError unavailable){return null;}}
  @Override void pauseVideo(){if(player!=null)try{if(player.isPlaying())player.pause();}catch(RuntimeException ignored){}}
  @Override void resumeVideo(){play();}
  @Override void release(){
   if(released)return;firstFrame=null;surface.setSurfaceTextureListener(null);if(prepareTimeout!=null){handler.removeCallbacks(prepareTimeout);prepareTimeout=null;}MediaPlayer m=player;player=null;
   if(m!=null){m.setOnErrorListener(null);m.setOnPreparedListener(null);m.setOnCompletionListener(null);m.setOnInfoListener(null);try{m.stop();}catch(RuntimeException ignored){}try{m.reset();}catch(RuntimeException ignored){}m.release();}
   if(attachedSurface!=null){attachedSurface.release();attachedSurface=null;}super.release();
  }
 }
}
