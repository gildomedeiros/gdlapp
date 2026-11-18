package au.com.gildo.vtc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

final class LiveMapView extends LinearLayout {
    private static final String[] SPEED_LABELS={"0.25×","0.5×","1×","2×","4×","8×"};
    private static final double[] SPEEDS={.25,.5,1,2,4,8};
    private static final int SEEK_STEPS=10000;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Supplier<Map<String,Object>> liveFrame;
    private final SharedPreferences preferences;
    private final WebView web;
    private final TextView status,time,rideIndicator;
    private final EditText key;
    private final Button recent,choose,play,end;
    private final LinearLayout playback;
    private final SeekBar seek;
    private final Spinner clipSelector;
    private MapReplay replay;
    private double speed=1;
    private long offset,started,clipStart,clipEnd,mapContext;
    private boolean playing,ready,visible,closed,loading;
    private String notice="";
    private final Runnable tick=this::tick;

    @SuppressLint("SetJavaScriptEnabled")
    LiveMapView(Context context,Supplier<Map<String,Object>> liveFrame,Runnable replayLast,
                Runnable replayFile,Runnable exitReplay){
        super(context);this.liveFrame=liveFrame;setOrientation(VERTICAL);
        preferences=context.getSharedPreferences("vtc_google_map",Context.MODE_PRIVATE);
        status=label("Live camera and surfer map");addView(status);
        rideIndicator=label("🌊 Wave ride active");
        rideIndicator.setTextColor(0xff75d6ff);rideIndicator.setVisibility(GONE);addView(rideIndicator);
        LinearLayout actions=new LinearLayout(context);addView(actions);
        recent=button(actions,"Replay last run",replayLast,true);
        choose=button(actions,"Replay from log file",replayFile,true);
        playback=new LinearLayout(context);playback.setOrientation(VERTICAL);addView(playback);
        playback.setVisibility(GONE);
        clipSelector=new Spinner(context);clipSelector.setContentDescription("Choose full run or a wave ride");
        playback.addView(clipSelector,new LayoutParams(-1,-2));
        clipSelector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,android.view.View view,int index,long id){
                if(replay==null||index<0||index>replay.rides.size())return;
                MapReplay.RideClip clip=index==0?null:replay.rides.get(index-1);
                clipStart=clip==null?0:clip.start;clipEnd=clip==null?replay.duration:clip.end;
                offset=clipStart;started=SystemClock.elapsedRealtime();playing=true;refresh();
            }
            public void onNothingSelected(AdapterView<?> parent){}
        });
        time=label("");playback.addView(time);
        seek=new SeekBar(context);seek.setMax(SEEK_STEPS);playback.addView(seek);
        LinearLayout row=new LinearLayout(context);playback.addView(row);
        play=button(row,"Pause",this::toggle,true);
        Spinner rate=new Spinner(context);
        rate.setContentDescription("Replay speed");
        rate.setAdapter(new ArrayAdapter<>(context,android.R.layout.simple_spinner_dropdown_item,SPEED_LABELS));
        row.addView(rate,new LayoutParams(0,-2,1));
        rate.setSelection(2);
        rate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,android.view.View view,int index,long id){
                if(replay!=null){offset=playhead();started=SystemClock.elapsedRealtime();}
                speed=SPEEDS[index];
            }
            public void onNothingSelected(AdapterView<?> parent){}
        });
        end=button(row,"Return to live",exitReplay,true);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){
                if(fromUser&&replay!=null){offset=clipStart+Math.round((clipEnd-clipStart)*progress/(double)SEEK_STEPS);
                    started=SystemClock.elapsedRealtime();refresh();}
            }
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });

        web=new WebView(context);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public void onPageFinished(WebView view,String url){ready=true;refresh();}
        });
        addView(web,new LayoutParams(-1,(int)(400*getResources().getDisplayMetrics().density)));
        addView(label("Camera–surfer line: green on target · orange panning · red panning and potentially out of frame · grey unconfirmed"));
        key=new EditText(context);key.setSingleLine(true);
        key.setTextColor(Color.WHITE);key.setHintTextColor(Color.LTGRAY);
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setHint("Google Maps JavaScript API key (optional)");key.setText(preferences.getString("key",""));addView(key);
        button(this,"Save map key / Refresh map",this::saveKey,false);
        addView(label("The diagram works offline. Google Maps receives the displayed coordinates when its background is enabled."));
        reload();
    }

    private TextView label(String text){
        TextView v=new TextView(getContext());v.setTextColor(Color.WHITE);
        v.setText(text);v.setPadding(0,6,0,6);return v;
    }
    private Button button(LinearLayout parent,String label,Runnable action,boolean weighted) {
        Button b=new Button(getContext());b.setAllCaps(false);b.setText(label);b.setOnClickListener(v->action.run());
        parent.addView(b,weighted?new LayoutParams(0,-2,1):new LayoutParams(-1,-2));return b;
    }
    private void saveKey(){
        String value=key.getText().toString().trim();
        if(!value.isEmpty()&&!value.matches("[A-Za-z0-9_-]{20,200}")){status.setText("Invalid map key format");return;}
        preferences.edit().putString("key",value).apply();reload();
    }
    private void reload(){
        ready=false;
        try(InputStream in=getContext().getAssets().open("vtc_live_map.html")){
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[4096];int count;
            while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
            String value=preferences.getString("key","");
            if(!value.matches("[A-Za-z0-9_-]{20,200}"))value="";
            String html=out.toString(StandardCharsets.UTF_8.name()).replace("__MAP_KEY__",value);
            web.loadDataWithBaseURL("https://vtc.local/",html,"text/html","UTF-8",null);
        }catch(Exception e){status.setText("Could not load the map: "+e.getMessage());}
    }

    void setVisibleOnScreen(boolean visible){
        this.visible=visible;handler.removeCallbacks(tick);
        if(visible)tick();
    }
    boolean replaying(){return replay!=null;}
    void loading(boolean value,String text){
        loading=value;notice=text;recent.setEnabled(!value&&replay==null);choose.setEnabled(!value&&replay==null);refresh();
    }
    void openReplay(MapReplay run){
        replay=run;clipStart=0;clipEnd=run.duration;offset=0;mapContext++;
        started=SystemClock.elapsedRealtime();playing=true;loading=false;notice="";
        java.util.List<String> clips=new java.util.ArrayList<>();
        clips.add("Full run");
        for(int i=0;i<run.rides.size();i++)
            clips.add("Wave ride "+(i+1)+" · "+displayTime(run.rides.get(i).rideStart));
        clipSelector.setAdapter(new ArrayAdapter<>(getContext(),android.R.layout.simple_spinner_dropdown_item,clips));
        clipSelector.setSelection(0);
        clipSelector.setVisibility(run.rides.isEmpty()?GONE:VISIBLE);
        playback.setVisibility(VISIBLE);recent.setEnabled(false);choose.setEnabled(false);play.setText("Pause");refresh();
    }
    void live(){
        replay=null;playing=false;loading=false;notice="";mapContext++;
        playback.setVisibility(GONE);recent.setEnabled(true);choose.setEnabled(true);refresh();
    }
    private long playhead() {
        return Math.min(clipEnd,offset+(playing?(long)((SystemClock.elapsedRealtime()-started)*speed):0));
    }
    private void toggle(){
        if(replay==null)return;
        offset=playhead();
        if(!playing&&offset>=clipEnd)offset=clipStart;
        playing=!playing;started=SystemClock.elapsedRealtime();refresh();
    }
    private static String displayTime(long ms){
        long seconds=ms/1000;
        return String.format(Locale.US,"%02d:%02d.%d",seconds/60,seconds%60,(ms%1000)/100);
    }
    private void tick(){
        if(!visible||closed)return;
        refresh();handler.postDelayed(tick,replay!=null&&playing?100:250);
    }
    private void refresh(){
        if(closed)return;
        Map<String,Object> frame;
        if(replay==null){
            status.setText(notice.isEmpty()?"Live map":notice);
            frame=liveFrame.get();
        }else{
            long position=playhead();
            if(position>=clipEnd){offset=position;playing=false;}
            play.setText(playing?"Pause":position>=clipEnd?"Play again":"Play");
            seek.setProgress(clipEnd>clipStart?(int)(SEEK_STEPS*(position-clipStart)/(clipEnd-clipStart)):0);
            time.setText(displayTime(position)+" / "+displayTime(clipEnd)+(clipStart>0?" · clip starts "+displayTime(clipStart):""));
            status.setText("Replay · GTi, LoRa and camera disconnected");
            frame=new java.util.LinkedHashMap<>(replay.at(position));
            frame.put("fitRange",replay.largestRange);
        }
        rideIndicator.setVisibility(Boolean.TRUE.equals(frame.get("rideActive"))?VISIBLE:GONE);
        if(ready&&(visible||loading))web.evaluateJavascript("window.showVtcFrame("+LogJson.encode(frame)+","+mapContext+")",null);
    }
    void close(){closed=true;handler.removeCallbacksAndMessages(null);web.stopLoading();web.destroy();}
}
