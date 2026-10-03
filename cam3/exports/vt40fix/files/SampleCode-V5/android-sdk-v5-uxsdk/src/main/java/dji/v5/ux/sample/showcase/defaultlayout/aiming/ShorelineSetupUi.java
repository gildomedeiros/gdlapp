package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Operational stopped-only shoreline setup; tuning remains JSON-only. */
public final class ShorelineSetupUi {
    private final Activity activity;
    private final YawAimingController controller;
    private ShorelineLibrary library;
    private String selected;
    private AlertDialog current;
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private void dismissCurrent(){if(current!=null)current.dismiss();current=null;}
    private void notice(String message){Toast.makeText(activity,message,Toast.LENGTH_LONG).show();}
    private LinearLayout column(){LinearLayout b=new LinearLayout(activity);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(16),dp(8),dp(16),dp(8));return b;}
    private void button(LinearLayout box,String title,Runnable action){Button b=new Button(activity);b.setText(title);b.setOnClickListener(v->action.run());box.addView(b);}
    public ShorelineSetupUi(Activity activity,YawAimingController controller){this.activity=activity;this.controller=controller;}
    private void error(String message){if(!activity.isFinishing())new AlertDialog.Builder(activity).setTitle("Shoreline setup").setMessage(message).setPositiveButton("OK",null).show();}
    private void task(java.util.concurrent.Callable<String> work,java.util.function.Consumer<String> done){controller.shorelineTask(work,result->{if(!activity.isFinishing())done.accept(result);},this::error);}
    public void show(){
        task(()->{library=ShorelineLibrary.parse(SharedConfigStorage.read(activity,SharedConfigStorage.SHORELINES));
            selected=VtJsonFields.string(StrictConfigJson.object(SharedConfigStorage.read(activity,SharedConfigStorage.SETTINGS)),"shorelineId");return "";},ignored->menu());
    }
    private void menu(){
        dismissCurrent();LinearLayout box=column();TextView state=new TextView(activity);
        String name="None";for(ShorelineLibrary.Profile p:library.profiles)if(p.id.equals(selected))name=p.name;
        state.setText("Selected shoreline: "+name+"\n"+(library.profiles.isEmpty()?"No saved shorelines yet. Capture A/B or enter coordinates below.":"Tap a shoreline name to select it for next Start."));box.addView(state);
        for(ShorelineLibrary.Profile p:library.profiles){
            button(box,(p.id.equals(selected)?"✓ ":"")+p.name,()->task(()->{ShorelineLibrary.parse(SharedConfigStorage.read(activity,SharedConfigStorage.SHORELINES)).find(p.id);select(p.id);return "Selected "+p.name;},message->{notice(message);show();}));
            button(box,"Review / Maps / manage · "+p.name,()->{dismissCurrent();profile(p);});
        }
        button(box,"Capture new shoreline · LoRa GPS",()->{dismissCurrent();nameForCapture();});
        button(box,"Enter A/B coordinates manually",()->{dismissCurrent();manual();});
        button(box,"Open Google Maps",this::openMaps);
        button(box,"Refresh saved shorelines",this::show);
        ScrollView scroll=new ScrollView(activity);scroll.addView(box);
        current=new AlertDialog.Builder(activity).setTitle("VT 4.0.1 · Shorelines").setView(scroll).setNegativeButton("Close",null).create();current.show();
    }
    private void openMaps(){
        AimingSession.Fix f=controller.shorelineMapFix();
        String url=f==null?"https://www.google.com/maps/":"https://www.google.com/maps/search/?api=1&query="+f.lat+","+f.lon;
        try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){error("No browser or Maps application available: "+e.getMessage());}
    }
    private void select(String id)throws Exception {
        String raw=SharedConfigStorage.read(activity,SharedConfigStorage.SETTINGS);
        // Validate every tuning field before preserving and changing the operational selection.
        VtSettingsConfig.parse(raw,RetreatJsonConfig.parse(SharedConfigStorage.read(activity,SharedConfigStorage.RETREAT)));
        JsonObject o=StrictConfigJson.object(raw);o.addProperty("shorelineId",id);
        SharedConfigStorage.writeVerified(activity,SharedConfigStorage.SETTINGS,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
    }
    private void profile(ShorelineLibrary.Profile p){
        String[] actions={"Select for next Start","Review line / Google Maps","Rename","Delete"};
        new AlertDialog.Builder(activity).setTitle(p.name).setItems(actions,(d,i)->{
            if(i==0)task(()->{ShorelineLibrary.parse(SharedConfigStorage.read(activity,SharedConfigStorage.SHORELINES)).find(p.id);select(p.id);return "Selected "+p.name+" for next Start";},message->{notice(message);show();});
            if(i==1)review(p,false);
            if(i==2){EditText name=text(p.name);new AlertDialog.Builder(activity).setTitle("Rename shoreline").setView(name).setPositiveButton("Save",(x,y)->{
                task(()->{List<ShorelineLibrary.Profile> ps=new ArrayList<>(ShorelineLibrary.parse(SharedConfigStorage.read(activity,SharedConfigStorage.SHORELINES)).profiles);
                    for(int j=0;j<ps.size();j++)if(ps.get(j).id.equals(p.id))ps.set(j,new ShorelineLibrary.Profile(p.id,name.getText().toString().trim(),p.geometry,p.method,p.capturedAt,p.aScatter,p.bScatter,p.aFixes,p.bFixes));
                    SharedConfigStorage.writeVerified(activity,SharedConfigStorage.SHORELINES,new ShorelineLibrary(ps).json());return "";},ignored->show());
            }).setNegativeButton("Cancel",null).show();}
            if(i==3)new AlertDialog.Builder(activity).setTitle("Delete "+p.name+"?").setMessage("Choose another shoreline first if this one is selected.").setPositiveButton("Delete",(x,y)->task(()->{
                JsonObject settings=StrictConfigJson.object(SharedConfigStorage.read(activity,SharedConfigStorage.SETTINGS));
                if(p.id.equals(VtJsonFields.string(settings,"shorelineId")))throw new IllegalArgumentException("This shoreline is selected. Select another profile before deleting it.");
                List<ShorelineLibrary.Profile> ps=new ArrayList<>(ShorelineLibrary.parse(SharedConfigStorage.read(activity,SharedConfigStorage.SHORELINES)).profiles);ps.removeIf(q->q.id.equals(p.id));
                SharedConfigStorage.writeVerified(activity,SharedConfigStorage.SHORELINES,new ShorelineLibrary(ps).json());return "";},ignored->show())).setNegativeButton("Cancel",null).show();
        }).setNegativeButton("Back",(d,i)->menu()).show();
    }
    private EditText text(String initial){EditText e=new EditText(activity);e.setSingleLine(true);e.setText(initial);return e;}
    private void nameForCapture(){
        if(controller.usesPhoneGps()){error("Select LoRa GPS using the GPS source control before capturing a shoreline.");return;}
        EditText name=text("");name.setHint("Beach / shoreline name");
        new AlertDialog.Builder(activity).setTitle("Capture shoreline · LoRa GPS").setMessage("Stand stationary with the LoRa GPS unit at point A on the shoreline. Capture 10 fresh fixes, then walk 50–100 m along the shoreline to point B.")
            .setView(name).setPositiveButton("Capture A",(d,i)->{
                String value=name.getText().toString().trim();if(value.isEmpty()){error("Enter a shoreline name");return;}
                capture("A",a->new AlertDialog.Builder(activity).setTitle("Point A captured").setMessage("Walk 50–100 m along the shoreline with the LoRa unit. Stand still at point B, then capture B.")
                    .setPositiveButton("Capture B",(x,y)->capture("B",b->{try{review(new ShorelineLibrary.Profile(UUID.randomUUID().toString(),value,new ShorelineGeometry(a.lat,a.lon,b.lat,b.lon,"leftOfAToB"),"lora",new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US).format(new Date()),a.scatter,b.scatter,a.fixes,b.fixes),true);}catch(Exception e){error(e.getMessage());}})).setNegativeButton("Cancel",null).show());
            }).setNegativeButton("Cancel",null).show();
    }
    private void capture(String label,java.util.function.Consumer<ShorelineCapture.Point> done){
        AtomicBoolean cancelled=new AtomicBoolean();TextView status=new TextView(activity);status.setPadding(24,24,24,24);status.setText("Waiting for fresh LoRa GPS");
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Capture point "+label+" · remain stationary").setView(status).setNegativeButton("Cancel",(d,i)->cancelled.set(true)).create();
        dialog.setOnCancelListener(d->cancelled.set(true));dialog.show();
        controller.captureShoreline(status::setText,p->{if(cancelled.get()||activity.isFinishing())return;dialog.dismiss();done.accept(p);},message->{dialog.dismiss();if(!cancelled.get())error(message);},cancelled::get);
    }
    private void manual(){
        LinearLayout box=column();String[] hints={"Name","Point A latitude","Point A longitude","Point B latitude","Point B longitude"};EditText[] fields=new EditText[5];
        for(int i=0;i<5;i++){fields[i]=text("");fields[i].setHint(hints[i]);if(i>0)fields[i].setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);box.addView(fields[i]);}
        TextView validation=new TextView(activity);box.addView(validation);ScrollView scroll=new ScrollView(activity);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Manual shoreline coordinates").setView(scroll).setPositiveButton("Review",null).setNegativeButton("Cancel",null).create();dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{ShorelineLibrary.Profile profile=new ShorelineLibrary.Profile(UUID.randomUUID().toString(),fields[0].getText().toString().trim(),
                new ShorelineGeometry(Double.parseDouble(fields[1].getText().toString()),Double.parseDouble(fields[2].getText().toString()),Double.parseDouble(fields[3].getText().toString()),Double.parseDouble(fields[4].getText().toString()),"leftOfAToB"),"manual",new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US).format(new Date()),0,0,0,0);
                dialog.dismiss();review(profile,true);
            }catch(Exception e){validation.setText("Check name and coordinates: "+e.getMessage());}
        });
    }
    private void maps(ShorelineGeometry g,boolean a){
        double lat=a?g.aLat:g.bLat,lon=a?g.aLon:g.bLon;
        String url="https://www.google.com/maps/search/?api=1&query="+lat+","+lon;
        try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception e){error("No browser or Maps application available: "+e.getMessage());}
    }
    private void review(ShorelineLibrary.Profile p,boolean save){
        LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);
        TextView details=new TextView(activity);details.setText(String.format(Locale.US,"A %.6f, %.6f\nB %.6f, %.6f\nSpacing %.1f m · A→B bearing %.0f°\nQuality A/B: %d/%d fixes, %.1f/%.1f m scatter\nChoose the sea side looking from A toward B. The diagram is schematic; check A and B in Maps.",p.geometry.aLat,p.geometry.aLon,p.geometry.bLat,p.geometry.bLon,YawAimingMath.distance(p.geometry.aLat,p.geometry.aLon,p.geometry.bLat,p.geometry.bLon),YawAimingMath.bearingToTarget(p.geometry.aLat,p.geometry.aLon,p.geometry.bLat,p.geometry.bLon),p.aFixes,p.bFixes,p.aScatter,p.bScatter));box.addView(details);
        final boolean[] left={p.geometry.seaSide.equals("leftOfAToB")};Preview preview=new Preview(activity,left[0]);box.addView(preview,new LinearLayout.LayoutParams(-1,230));
        RadioGroup sides=new RadioGroup(activity);RadioButton l=new RadioButton(activity),r=new RadioButton(activity);l.setText("Sea is LEFT of A → B");r.setText("Sea is RIGHT of A → B");l.setId(View.generateViewId());r.setId(View.generateViewId());sides.addView(l);sides.addView(r);sides.check(left[0]?l.getId():r.getId());
        sides.setOnCheckedChangeListener((group,id)->{left[0]=id==l.getId();preview.left=left[0];preview.invalidate();});sides.setEnabled(save);l.setEnabled(save);r.setEnabled(save);box.addView(sides);
        Button mapA=new Button(activity),mapB=new Button(activity);mapA.setText("Check point A in Google Maps");mapB.setText("Check point B in Google Maps");mapA.setOnClickListener(v->maps(p.geometry,true));mapB.setOnClickListener(v->maps(p.geometry,false));box.addView(mapA);box.addView(mapB);
        ScrollView scroll=new ScrollView(activity);scroll.addView(box);
        AlertDialog.Builder builder=new AlertDialog.Builder(activity).setTitle("Review · "+p.name).setView(scroll).setNegativeButton("Close",null);
        if(save)builder.setPositiveButton("Save & select",(d,i)->task(()->{
            ShorelineGeometry g=new ShorelineGeometry(p.geometry.aLat,p.geometry.aLon,p.geometry.bLat,p.geometry.bLon,left[0]?"leftOfAToB":"rightOfAToB");
            List<ShorelineLibrary.Profile> ps=new ArrayList<>(ShorelineLibrary.parse(SharedConfigStorage.read(activity,SharedConfigStorage.SHORELINES)).profiles);
            ps.add(new ShorelineLibrary.Profile(p.id,p.name,g,p.method,p.capturedAt,p.aScatter,p.bScatter,p.aFixes,p.bFixes));
            // Validate selection write before publishing the profile. Each file is backed up and verified.
            VtSettingsConfig.parse(SharedConfigStorage.read(activity,SharedConfigStorage.SETTINGS),RetreatJsonConfig.parse(SharedConfigStorage.read(activity,SharedConfigStorage.RETREAT)));
            SharedConfigStorage.writeVerified(activity,SharedConfigStorage.SHORELINES,new ShorelineLibrary(ps).json());
            try{select(p.id);}catch(Exception e){throw new IllegalArgumentException("Shoreline saved, but selection failed. Select it from Shorelines. "+e.getMessage(),e);}return "Shoreline saved and selected for next Start";
        },message->{notice(message);show();}));builder.show();
    }
    private static final class Preview extends View {
        boolean left;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Preview(Context context,boolean left){super(context);this.left=left;}
        @Override protected void onDraw(Canvas c){
            float y=getHeight()/2f,start=40,end=getWidth()-40,mid=getWidth()/2f,arrow=left?y-65:y+65;
            p.setColor(Color.rgb(20,150,210));p.setStrokeWidth(4);c.drawLine(start,y,end,y,p);p.setTextSize(28);c.drawText("A",start,y+30,p);c.drawText("B →",end-55,y+30,p);
            c.drawLine(mid,y,mid,arrow,p);float sign=left?1:-1;c.drawLine(mid,arrow,mid-12,arrow+sign*16,p);c.drawLine(mid,arrow,mid+12,arrow+sign*16,p);c.drawText("SEA",mid+20,arrow,p);
        }
    }
}
