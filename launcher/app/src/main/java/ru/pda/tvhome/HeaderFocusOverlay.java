package ru.pda.tvhome;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.widget.FrameLayout;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** One moving contour for the entire top bar, matching the tile spring. */
final class HeaderFocusOverlay extends View {
    private final float[] bounds = new float[5];
    private final SpringAnimation[] springs = new SpringAnimation[5];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean placed;
    private final boolean[] resetSpring = new boolean[5];
    private View anchor;
    private FrameLayout anchorRoot;
    private boolean anchorAnimated,anchorTileShape,forcePlacement;
    private float anchorRadiusDp = -1;
    private final Matrix targetMatrix=new Matrix();
    private final RectF targetRect=new RectF();
    private final float[] goal=new float[5],lastGoal=new float[5];
    private final android.view.ViewTreeObserver.OnPreDrawListener tracker=()->{
        if(getVisibility()==VISIBLE&&anchor!=null&&anchor.isAttachedToWindow())alignAnchor();
        return true;
    };
    HeaderFocusOverlay(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        for (int i = 0; i < springs.length; i++) springs[i] = makeSpring(i);
    }
    private SpringAnimation makeSpring(int axis) {
        SpringAnimation spring = new SpringAnimation(new FloatValueHolder()).setSpring(
                new SpringForce().setStiffness(axis == 4 ? 900f : 260f)
                        .setDampingRatio(axis == 4 ? 1f : .58f));
        spring.addUpdateListener((animation, value, velocity) -> { bounds[axis] = value; invalidate(); });
        return spring;
    }
    void clear() {
        for (int i = 0; i < springs.length; i++) { springs[i].cancel(); resetSpring[i] = true; }
        anchor = null; anchorRoot = null; placed = false;
        setVisibility(INVISIBLE);
    }
    void pointAt(View target, FrameLayout root, boolean animate) { pointAt(target, root, animate, false); }
    void pointAt(View target, FrameLayout root, boolean animate, boolean tileShape) {
        anchorRadiusDp = -1;
        anchor=target;anchorRoot=root;anchorAnimated=animate;anchorTileShape=tileShape;forcePlacement=true;
        setVisibility(VISIBLE);alignAnchor();
    }
    void pointAtRounded(View target, FrameLayout root, boolean animate, float radiusDp) {
        anchorRadiusDp=radiusDp;
        anchor=target;anchorRoot=root;anchorAnimated=animate;forcePlacement=true;
        setVisibility(VISIBLE);alignAnchor();
    }
    private void alignAnchor(){
        if(anchor.getWidth()<1||anchor.getHeight()<1)return;
        targetMatrix.reset();
        View current=anchor;
        while(current!=anchorRoot){
            if(!(current.getParent() instanceof View))return;
            View parent=(View)current.getParent();
            targetMatrix.postConcat(current.getMatrix());
            targetMatrix.postTranslate(current.getLeft()-parent.getScrollX(),current.getTop()-parent.getScrollY());
            current=parent;
        }
        targetRect.set(0,0,anchor.getWidth(),anchor.getHeight());targetMatrix.mapRect(targetRect);
        float density=getResources().getDisplayMetrics().density,pad=3*density;
        float scale=Math.min(targetRect.width()/anchor.getWidth(),targetRect.height()/anchor.getHeight());
        goal[0]=targetRect.left-pad;goal[1]=targetRect.top-pad;
        goal[2]=targetRect.width()+pad*2;goal[3]=targetRect.height()+pad*2;
        goal[4]=anchorRadiusDp>=0?anchorRadiusDp*density*scale+pad:
                anchorTileShape?AppAdapter.cornerRadius(anchor.getWidth(),density)*scale+pad:targetRect.height()/2+pad;
        boolean changed=!placed;
        for(int i=0;i<springs.length;i++){
            if(!placed||forcePlacement||Math.abs(lastGoal[i]-goal[i])>.05f){
                if(!placed||!anchorAnimated){springs[i].cancel();bounds[i]=goal[i];resetSpring[i]=true;}
                else {
                    if(resetSpring[i]) { springs[i]=makeSpring(i);springs[i].setStartValue(bounds[i]).setStartVelocity(0f);resetSpring[i]=false; }
                    springs[i].animateToFinalPosition(goal[i]);
                }
                changed=true;lastGoal[i]=goal[i];
            }
        }
        placed=true;forcePlacement=false;if(changed)invalidate();
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();getViewTreeObserver().addOnPreDrawListener(tracker);}
    @Override protected void onDraw(Canvas canvas) {
        if (!placed) return;
        RectF r = new RectF(bounds[0], bounds[1], bounds[0]+Math.max(1,bounds[2]), bounds[1]+Math.max(1,bounds[3]));
        // Variant 2: one thin contour only — no plate, no glow, no filled circle.
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
        paint.setColor(0x66FFFFFF);
        canvas.drawRoundRect(r,bounds[4],bounds[4],paint);
    }
    @Override protected void onDetachedFromWindow() { if(getViewTreeObserver().isAlive())getViewTreeObserver().removeOnPreDrawListener(tracker);for(SpringAnimation spring:springs)spring.cancel();anchor=null;anchorRoot=null;super.onDetachedFromWindow(); }
}
