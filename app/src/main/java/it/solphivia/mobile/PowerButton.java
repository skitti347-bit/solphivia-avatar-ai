package it.solphivia.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

/** Round shutdown control with a drawn power symbol, independent of installed fonts. */
final class PowerButton extends View {
    private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    PowerButton(Context context){
        super(context);
        setContentDescription("Spegni Solphivia: chiudi avatar, voce e microfono");
        setFocusable(true);setClickable(true);
        GradientDrawable circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);circle.setColor(0xFF131A2E);circle.setStroke(Math.max(1,Math.round(getResources().getDisplayMetrics().density)),0xFF2A3554);
        GradientDrawable mask=new GradientDrawable();mask.setShape(GradientDrawable.OVAL);mask.setColor(0xFF131A2E);
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FF2EC8),circle,mask));
        setElevation(7*getResources().getDisplayMetrics().density);
        setOnClickListener(v->AppControls.close(context));
    }
    @Override public CharSequence getAccessibilityClassName(){return "android.widget.Button";}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.23f;
        ink.setColor(0xFFFF2EC8);ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(Math.max(2,r*.25f));ink.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawArc(cx-r,cy-r,cx+r,cy+r,-45,270,false,ink);
        canvas.drawLine(cx,cy-r*1.25f,cx,cy-r*.05f,ink);
    }
}
