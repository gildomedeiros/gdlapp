from pathlib import Path
import json,hashlib
root=Path('C:/Users/gildo/.codex/worktrees/vt-28/gdlapp/vt')
out=Path(__file__).parent/'files'
pk='SampleCode-V5/android-sdk-v5-uxsdk/src/main/java/dji/v5/ux/sample/showcase/defaultlayout/'
p=pk+'aiming/'
t='SampleCode-V5/android-sdk-v5-uxsdk/src/test/java/dji/v5/ux/sample/showcase/defaultlayout/aiming/'
touched=set()
def read(n):return ((out/n) if n in touched else (root/n)).read_text(encoding='utf-8-sig')
def write(n,s):
 q=out/n;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(s,encoding='utf-8');touched.add(n)
def edit(n,a,b):
 s=read(n);assert a in s,(n,a[:80]);write(n,s.replace(a,b))
write(p+'FullLogOpenPolicy.java','''package dji.v5.ux.sample.showcase.defaultlayout.aiming;
/** One opening attempt per foreground entry or explicit enable. Busy close postpones the attempt. */
public final class FullLogOpenPolicy {
    public boolean wanted=true;
    private boolean pending;
    public void foregroundEntered(){pending=wanted;}
    public void request(boolean enable){wanted=enable;pending=enable;}
    public boolean shouldOpen(boolean foreground,boolean permission,boolean enabled,boolean busy){
        if(!foreground||!wanted||!pending||!permission||busy)return false;
        pending=false;return !enabled;
    }
}
''')
n=p+'YawAimingController.java'
edit(n,'private volatile boolean fullLogWanted=true;','private final FullLogOpenPolicy fullLogOpening=new FullLogOpenPolicy();\n    private volatile boolean fullLogWanted=true;\n    public boolean fullLogRequested(){return fullLogWanted;}\n    public AimingSession.Fix shorelineMapFix(){return getTargetFix();}')
a=read(n).index('    public void setFullLogEnabled(');b=read(n).index('    private final Context appContext;',a)
s=read(n);s=s[:a]+'''    public void setFullLogEnabled(boolean enabled) {
        fullLogWanted=enabled;
        executor.execute(()->{
            fullLogOpening.request(enabled);
            if(!enabled)fullLog.disable("user_off");else ensureFullLogOpen();
            diagnostic("logging_mode",enabled?"Full requested":"Minimal requested");
        });
    }
    private void ensureFullLogOpen() {
        boolean permission=android.os.Build.VERSION.SDK_INT>=29 || appContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(fullLogOpening.shouldOpen(foreground,permission,fullLog.enabled(),fullLog.busy())) {
            fullLog.enable();fullLog.record("capture_source","source",usePhone?"phone":"lora_wifi");
        }
    }
'''+s[b:];write(n,s)
edit(n,'        executor.execute(() -> {\n            try {\n                // CAM3 v2.2: A failed registration','        executor.execute(() -> {\n            fullLogOpening.foregroundEntered();\n            ensureFullLogOpen();\n            try {\n                // CAM3 v2.2: A failed registration')
a=read(n).index('                    boolean storageReady=');b=read(n).index('                    aircraft.start();',a);s=read(n);s=s[:a]+s[b:];write(n,s)
edit(n,'    private void tick() {','    private void tick() {\n        ensureFullLogOpen();')
edit(n,'String message=detail.startsWith("Cannot start:")?detail:"Cannot start: "+detail;','String message=(detail.startsWith("Cannot start:")||detail.startsWith("Shoreline setup required:"))?detail:"Cannot start: "+detail;')
n=p+'VtSessionConfig.java'
edit(n,'    public interface Reader {','    public static final class ShorelineSetupRequired extends IllegalArgumentException {\n        ShorelineSetupRequired(String message){super("Shoreline setup required: "+message);}\n    }\n    public interface Reader {')
edit(n,'                ShorelineLibrary.Profile p=l.find(c.shorelineId);','                if(l.profiles.isEmpty())throw new ShorelineSetupRequired("No saved shorelines yet. Capture shoreline points A/B or enter their coordinates, then save and select the shoreline.");\n                if(c.shorelineId.trim().isEmpty())throw new ShorelineSetupRequired("No shoreline selected. Open Shorelines and tap a saved shoreline to select it.");\n                ShorelineLibrary.Profile p;\n                try{p=l.find(c.shorelineId);}catch(IllegalArgumentException missing){throw new ShorelineSetupRequired("The selected shoreline is no longer available. Open Shorelines and select a saved shoreline.");}')
edit(n,'}catch(Exception e){throw new IllegalArgumentException("Cannot start: "+file+" — "+e.getMessage()', '}catch(ShorelineSetupRequired e){throw e;}catch(Exception e){throw new IllegalArgumentException("Cannot start: "+file+" — "+e.getMessage()')
n=pk+'DefaultLayoutActivity.java'
edit(n,'private YawAimingController yawAimingController;','private YawAimingController yawAimingController;\n    private android.app.AlertDialog configurationProblemDialog;')
edit(n,'All three JSON files will be kept there. Retreat settings are in vt_retreat_settings.json.','All five JSON files will be kept there. Movement settings are in vt_settings.json; saved shoreline coordinates are in vt_shorelines.json. Retreat settings are in vt_retreat_settings.json.')
edit(n,'Toast.makeText(this, granted ? "Storage allowed; enable Full Log from the menu"','if(granted && yawAimingController!=null)yawAimingController.setFullLogEnabled(true);\n                Toast.makeText(this, granted ? "Storage allowed; Full Log enabled"')
edit(n,'new android.app.AlertDialog.Builder(this).setTitle("Start blocked — configuration")\n                    .setMessage(message).setPositiveButton(android.R.string.ok,null).show();','showConfigurationProblem(message);')
edit(n,'.setChecked(yawAimingController.fullLogEnabled())','.setChecked(yawAimingController.fullLogRequested())')
edit(n,'boolean enable = !yawAimingController.fullLogEnabled();','boolean enable = !yawAimingController.fullLogRequested();')
edit(n,'    private void showMovementSettings() {','''    private void showConfigurationProblem(String message) {
        if(configurationProblemDialog!=null&&configurationProblemDialog.isShowing())return;
        boolean setup=message.startsWith("Shoreline setup required:");
        configurationProblemDialog=new android.app.AlertDialog.Builder(this)
            .setTitle(setup?"Set up shoreline before Start":"Start blocked — configuration")
            .setMessage(setup?message.substring("Shoreline setup required:".length()).trim():message)
            .setPositiveButton("Open Shorelines",(d,w)->new dji.v5.ux.sample.showcase.defaultlayout.aiming.ShorelineSetupUi(this,yawAimingController).show())
            .setNeutralButton("Configuration folder",(d,w)->chooseConfigurationFolder())
            .setNegativeButton("Close",null).create();
        configurationProblemDialog.setOnDismissListener(d->configurationProblemDialog=null);
        configurationProblemDialog.show();
    }
    private void showMovementSettings() {''')
# Keep validation inline and Maps visible before A/B have been captured.
n=p+'ShorelineSetupUi.java'
edit(n,'    private String selected;','    private String selected;\n    private AlertDialog current;\n    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}\n    private void dismissCurrent(){if(current!=null)current.dismiss();current=null;}\n    private void notice(String message){Toast.makeText(activity,message,Toast.LENGTH_LONG).show();}\n    private LinearLayout column(){LinearLayout b=new LinearLayout(activity);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(16),dp(8),dp(16),dp(8));return b;}\n    private void button(LinearLayout box,String title,Runnable action){Button b=new Button(activity);b.setText(title);b.setOnClickListener(v->action.run());box.addView(b);}')
a=read(n).index('    private void menu(){');b=read(n).index('    private void select(',a);s=read(n);s=s[:a]+'''    private void menu(){
        dismissCurrent();LinearLayout box=column();TextView state=new TextView(activity);
        String name="None";for(ShorelineLibrary.Profile p:library.profiles)if(p.id.equals(selected))name=p.name;
        state.setText("Selected shoreline: "+name+"\\n"+(library.profiles.isEmpty()?"No saved shorelines yet. Capture A/B or enter coordinates below.":"Tap a shoreline name to select it for next Start."));box.addView(state);
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
'''+s[b:];write(n,s)
edit(n,'return "Selected "+p.name+" for next Start";},this::error);','return "Selected "+p.name+" for next Start";},message->{notice(message);show();});')
edit(n,'},this::error));builder.show();','},message->{notice(message);show();}));builder.show();')
a=read(n).index('    private void manual(){');b=read(n).index('    private void maps(',a);s=read(n);s=s[:a]+'''    private void manual(){
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
'''+s[b:];write(n,s)
# Version identifiers distinguish the repaired APK from the reported build.
edit('SampleCode-V5/android-sdk-v5-sample/build.gradle','versionCode 23','versionCode 24')
edit('SampleCode-V5/android-sdk-v5-sample/build.gradle','versionName "4.0"','versionName "4.0.1"')
edit(p+'FullSessionLog.java','"version", "4.0"','"version", "4.0.1"')
for f in (root/t).glob('*.java'):
 s=f.read_text(encoding='utf-8-sig')
 if '\\"version\\":\\"4.0\\"' in s:write(t+f.name,s.replace('\\"version\\":\\"4.0\\"','\\"version\\":\\"4.0.1\\"'))
# Meaningful race policy checks plus first-run/missing-selection cases against production loader.
n=t+'Vt40Test.java'
edit(n,'    public static void main(String[] args)', '''    static void fullLogReopen(){
        FullLogOpenPolicy p=new FullLogOpenPolicy();check(p.wanted,"full logging requested by default");
        p.foregroundEntered();check(!p.shouldOpen(true,true,false,true),"busy close postpones reopen");
        check(p.shouldOpen(true,true,false,false),"reopen after asynchronous close completes");
        check(!p.shouldOpen(true,true,false,false),"opening failure does not loop alerts");
        p.request(false);p.foregroundEntered();check(!p.shouldOpen(true,true,false,false),"explicit off respected");
        p.request(true);check(!p.shouldOpen(true,false,false,false),"permission required");
        check(p.shouldOpen(true,true,false,false),"grant opens requested log");
        p.foregroundEntered();check(!p.shouldOpen(false,true,false,false),"background does not open");
        check(p.shouldOpen(true,true,false,false),"next foreground opens");
    }
    public static void main(String[] args)''')
edit(n,'geometry();fixedPlans();capture();config(args[0]);','fullLogReopen();geometry();fixedPlans();capture();config(args[0]);')
edit(n,'        files.put("vt_settings.json",chosen);files.put("vt_shorelines.json",library);','''        files.put("vt_settings.json",chosen);files.put("vt_shorelines.json",library);
        files.put("vt_settings.json",settings);
        try{VtSessionConfig.load40(files::get);throw new AssertionError("blank selection accepted");}catch(VtSessionConfig.ShorelineSetupRequired expected){check(expected.getMessage().contains("No shoreline selected"),"blank selection gives setup action");}
        files.put("vt_shorelines.json","{\\"version\\":1,\\"shorelines\\":[]}");
        try{VtSessionConfig.load40(files::get);throw new AssertionError("empty library accepted");}catch(VtSessionConfig.ShorelineSetupRequired expected){check(expected.getMessage().contains("No saved shorelines"),"empty library gives capture action");}
        files.put("vt_settings.json",chosen);files.put("vt_shorelines.json",library);''')
edit('tools/test-aiming.ps1','"$source/ShorelineCapture.java"','"$source/ShorelineCapture.java" "$source/FullLogOpenPolicy.java"')
write('Docs/VT_4.0.1.md','''# VT 4.0.1 — first-use shoreline setup and Full Log repair

VersionName 4.0.1 / versionCode 24. Corrects the reported VT 4.0 setup experience without changing flight geometry.

- Shorelines explicitly displays the selected profile or an empty-library explanation. Tap a saved name to select it directly; Review/Maps/manage remains separate. Successful save/selection refreshes the library and uses a confirmation toast, not an error dialog.
- Google Maps is accessible from the main Shorelines screen before capture. It opens the current target location when available, otherwise the general map. A/B point links remain in Review after capture/manual entry. Maps remains external; the straight A/B line and sea arrow are drawn in VT.
- Empty library, blank selection and deleted selected profiles generate a short first-use explanation with an Open Shorelines action. Configuration error dialogs are deduplicated. Manual-entry validation stays inline and retains entered values; the form scrolls with the keyboard.
- Full Log is requested ON by default. A busy asynchronous close no longer loses a foreground reopening request. Logging retries after close finishes, and permission grant enables it automatically. A failed opening produces one error per opening request instead of retrying every control tick. Explicit user OFF remains respected. The menu checkmark shows the requested state; Details reports the actual file status/errors.
- Folder help now names all five JSON files. Tuning forms remain removed by the agreed JSON-only design. No fake shoreline is seeded: coordinates and sea side must be captured/entered and selected.

Defaults: Come-to-me enabled, retreat enabled, Full Log requested enabled, mode Front, filming 28m, retreat 23m, GPS source LoRa. Phone GPS and touch lock start off; flight control requires explicit Start. No shoreline is selected until setup. Existing JSON is preserved, including user values that differ from defaults.

Validation: complete control/GPS/ride/return/retreat/config/log suite plus startup setup and asynchronous logging-policy regression cases. Android debug APK is built offline. Physical UI and flight behavior require device verification; screenshots alone do not prove Maps launch or storage writes succeeded on the device.
''')
manifest=[]
for n in sorted(touched):
 old=root/n;new=out/n;manifest.append(dict(path=n,originalSha256=hashlib.sha256(old.read_bytes()).hexdigest() if old.exists() else None,stagedSha256=hashlib.sha256(new.read_bytes()).hexdigest()))
(out.parent/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('Staged repair',len(manifest),'files')
