package au.com.gildo.fm;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Objects;
import java.util.function.Supplier;

/** Offline, north-up map. Only this visible dialog samples the cached display data. */
final class LiveMapDialog extends Dialog {
    private final Supplier<LiveMapFrame> source;
    private final Plot plot;
    private final TextView status;
    private LiveMapFrame frame;
    private final Runnable refresh=new Runnable() {
        public void run() {
            LiveMapFrame next=source.get();
            if(!Objects.equals(next,frame)) {
                frame=next;
                status.setText(frame==null?"Waiting for FM":frame.status());
                plot.invalidate();
            }
            plot.postDelayed(this,1000);
        }
    };

    LiveMapDialog(Activity owner,Supplier<LiveMapFrame> source) {
        super(owner);
        this.source=source;
        setTitle("Live Map");
        LinearLayout body=new LinearLayout(owner);
        body.setOrientation(LinearLayout.VERTICAL);
        status=new TextView(owner);
        status.setPadding(16,8,16,8);
        status.setText("Waiting for FM");
        body.addView(status);
        plot=new Plot(owner);
        body.addView(plot,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout controls=new LinearLayout(owner);
        body.addView(controls);
        button(controls,"−",()->plot.zoom(.67f));
        button(controls,"+",()->plot.zoom(1.5f));
        button(controls,"Fit",()->{plot.zoom=1;plot.dx=plot.dy=0;plot.invalidate();});
        button(controls,"Close",this::dismiss);
        setContentView(body);
    }
    private void button(LinearLayout bar,String label,Runnable click) {
        Button b=new Button(getContext());
        b.setAllCaps(false); b.setText(label); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setOnClickListener(v->click.run()); bar.addView(b,new LinearLayout.LayoutParams(0,-2,1));
    }
    @Override protected void onStart() {
        super.onStart();
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);
        plot.post(refresh);
    }
    @Override protected void onStop() { plot.removeCallbacks(refresh); super.onStop(); }

    private final class Plot extends View {
        private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d=getResources().getDisplayMetrics().density;
        private float zoom=1,dx,dy,lastX,lastY,cx,cy,scale;
        private static final int WHITE=0xffe8f0f4,YELLOW=0xffffd43b,BLUE=0xff64b5ff,GREY=0xffb6bec6;
        Plot(Activity activity) { super(activity); setBackgroundColor(0xff172632); setContentDescription("North-up live tracking map"); }
        void zoom(float factor) { zoom=Math.max(.25f,Math.min(16,zoom*factor)); invalidate(); }
        @Override public boolean onTouchEvent(MotionEvent e) {
            float x=e.getX(),y=e.getY();
            if(e.getAction()==MotionEvent.ACTION_MOVE) {dx+=x-lastX;dy+=y-lastY;invalidate();}
            lastX=x; lastY=y; return true;
        }
        private void text(Canvas c,String label,float x,float y,int color) {
            ink.setColor(color);ink.setStyle(Paint.Style.FILL);ink.setTextSize(14*d);c.drawText(label,x,y,ink);
        }
        private void line(Canvas c,float x,float y,int color,float thickness) {
            ink.setColor(color);ink.setStrokeWidth(thickness*d);c.drawLine(cx,cy,x,y,ink);
        }
        private void point(Canvas c,GoTo.Point p,String label,int color,int markerColor,float labelOffset) {
            if(p==null)return;
            double a=Math.toRadians(frame.bearing(p)),dist=frame.distance(p)*scale;
            float x=cx+(float)(Math.sin(a)*dist),y=cy-(float)(Math.cos(a)*dist);
            line(c,x,y,color,1.5f);
            ink.setColor(markerColor);c.drawCircle(x,y,4*d,ink);
            text(c,label,x+5*d,y+labelOffset*d,markerColor);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            text(c,"N ↑",10*d,20*d,WHITE);
            if(frame==null||frame.mount()==null) {text(c,"Waiting for mount coordinates",10*d,45*d,WHITE);return;}
            double range=frame.range(),landmark=frame.distance(frame.landmark()),width=frame.width();
            double extent=Math.max(5,Double.isFinite(landmark)?landmark:0);
            if(Double.isFinite(range))extent=Math.max(extent,Math.hypot(range,Double.isFinite(width)?width/2:0));
            scale=(float)(Math.min(getWidth(),getHeight())*.37/extent)*zoom;
            cx=getWidth()*.5f+dx;cy=getHeight()*.5f+dy;
            if(frame.heading()!=null) {
                double a=Math.toRadians(frame.heading());
                float length=(float)((Double.isFinite(range)&&range>=.1?range:extent*.85)*scale);
                float x=cx+(float)Math.sin(a)*length,y=cy-(float)Math.cos(a)*length;
                if(Double.isFinite(width)) {
                    float half=(float)(width*scale/2);
                    float vx=(float)Math.cos(a)*half,vy=(float)Math.sin(a)*half;
                    Path wedge=new Path();wedge.moveTo(cx,cy);wedge.lineTo(x+vx,y+vy);wedge.lineTo(x-vx,y-vy);wedge.close();
                    ink.setColor(frame.coverageKnown()?0x554dae90:0x55898989);c.drawPath(wedge,ink);
                }
                line(c,x,y,YELLOW,3);
                text(c,"Camera heading",x+3*d,y-9*d,YELLOW);
            }
            point(c,frame.landmark(),"Landmark",GREY,GREY,15);
            boolean moving="Panning".equals(frame.pan())||"Moving".equals(frame.pan());
            int color=frame.coverageKnown()&&!frame.inFrame()?0xffff786e:
                moving?0xffffae45:frame.inFrame()?0xff8dd397:GREY;
            point(c,frame.surfer(),frame.gpsFresh()?"Surfer":"Surfer (stale)",color,WHITE,-3);
            ink.setColor(BLUE);c.drawCircle(cx,cy,5*d,ink);text(c,"Mount",cx+6*d,cy+16*d,BLUE);
        }
    }
}
