package ru.pda.tvhome;
import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.RecyclerView;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntToDoubleFunction;
/** Whole-row opacity follows scroll progress, including recycled rows returning from above. */
public final class TileViewport extends FrameLayout {
 private RecyclerView grid;
 private int plannedDy, travelled;
 private final Map<Integer,Float> starts=new HashMap<>(), ends=new HashMap<>();
 private IntToDoubleFunction baseAlpha=position->1d;
 public TileViewport(Context c,AttributeSet a){super(c,a);}
 void setBaseAlphaProvider(IntToDoubleFunction provider){baseAlpha=provider;}
 void setTopFadeLength(int ignored){}
 private int headerEdge(){View buttons=getRootView().findViewById(R.id.topButtons);if(buttons==null)return 0;int[] h=new int[2],g=new int[2];buttons.getLocationOnScreen(h);grid.getLocationOnScreen(g);return h[1]+buttons.getHeight()-g[1];}
 void prepareScroll(int dy){
  applyRows();plannedDy=dy;travelled=0;starts.clear();ends.clear();int edge=headerEdge();
  for(int i=0;i<grid.getChildCount();i++){View child=grid.getChildAt(i);int position=grid.getChildAdapterPosition(child);if(position<0)continue;child.animate().cancel();starts.put(position,child.getAlpha());ends.put(position,child.getTop()-dy<edge?0f:(float)baseAlpha.applyAsDouble(position));}
 }
 @Override protected void onFinishInflate(){super.onFinishInflate();grid=findViewById(R.id.grid);
  grid.addOnScrollListener(new RecyclerView.OnScrollListener(){@Override public void onScrolled(RecyclerView v,int dx,int dy){
   if(plannedDy!=0)travelled+=dy;applyRows();if(plannedDy!=0&&(float)travelled/plannedDy>=1f){plannedDy=0;starts.clear();ends.clear();}
  }});
 }
 private void applyRows(){
  if(grid==null)return;int edge=headerEdge();float progress=plannedDy==0?1f:Math.max(0f,Math.min(1f,(float)travelled/plannedDy));
  for(int i=0;i<grid.getChildCount();i++){
   View child=grid.getChildAt(i);int position=grid.getChildAdapterPosition(child);if(position<0)continue;
   float base=(float)baseAlpha.applyAsDouble(position),alpha;
   if(plannedDy==0)alpha=child.getTop()<edge?0f:base;
   else{
    // A newly attached holder has no trustworthy alpha: Android or the adapter may
    // have restored it to one. Reconstruct its endpoints from its row coordinates.
    if(!starts.containsKey(position)){starts.put(position,child.getTop()+travelled<edge?0f:base);ends.put(position,child.getTop()-(plannedDy-travelled)<edge?0f:base);}
    float from=starts.get(position),to=ends.get(position);alpha=from+(to-from)*progress;
   }
   child.setAlpha(alpha);
  }
 }
 // Apply after adapter attachment/layout updates and before any child can be drawn.
 @Override protected void dispatchDraw(Canvas canvas){applyRows();super.dispatchDraw(canvas);}
}
