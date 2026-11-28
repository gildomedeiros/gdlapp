package au.com.gildo.fm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** UI-thread state. Typing edits a draft. Only completed edits go to the settings worker. */
final class SettingsAutosave {
    record Edit(int index, String value) {
        void apply(String[] values) {
            if (index == MountCoordinates.LATITUDE || index == LandmarkCalibration.LANDMARK_LAT) {
                String label = index == MountCoordinates.LATITUDE ? MountCoordinates.KEY : LandmarkCalibration.COORDINATES;
                if (value.trim().isEmpty() && !values[index].isEmpty())
                    throw new IllegalArgumentException(label + " needs both latitude and longitude");
                MountCoordinates.apply(value, values, index, index + 1, label);
            } else {
                values[index] = value;
                FmService.validateEditedField(values, index);
            }
        }
    }

    interface Writer { void save(Edit edit, Consumer<String> completed); }

    private final Writer writer;
    private final BiConsumer<Edit,String> status;
    private final Map<Integer, Edit> drafts = new LinkedHashMap<>();
    private final Map<Integer, Edit> queued = new LinkedHashMap<>();
    private Edit writing;
    private Runnable afterSave;
    private Runnable afterPendingWrites;

    SettingsAutosave(Writer writer, BiConsumer<Edit,String> status) {
        this.writer = writer;
        this.status = status;
    }

    static boolean editable(boolean loaded,boolean liveTracking,boolean bothMotorsStopped) {
        return loaded && (!liveTracking || bothMotorsStopped);
    }

    void edit(int index, String value) { drafts.put(index, new Edit(index, value)); }
    boolean hasDraft(int index) { return drafts.containsKey(index); }
    List<Edit> drafts() { return new ArrayList<>(drafts.values()); }
    boolean waitingForAction() { return afterSave != null || afterPendingWrites != null; }

    // A GPS capture replaces the landmark even if an earlier manual edit failed validation.
    void afterPendingWrites(Runnable action) {
        if (waitingForAction()) return;
        afterPendingWrites = action;
        drain();
    }

    Edit draft(int index) { return drafts.get(index); }
    void discardDraft(Edit original) {
        if(original!=null && drafts.get(original.index())==original) drafts.remove(original.index());
    }

    void finishField(int index) {
        Edit draft = drafts.get(index);
        if (draft != null) queue(draft);
        drain();
    }

    void finishAll(Runnable success) {
        if (waitingForAction()) return;
        afterSave = success;
        for (Edit draft : drafts.values()) queue(draft);
        drain();
    }

    private void queue(Edit edit) {
        if (writing == edit) return;
        queued.put(edit.index(), edit);
    }

    private void drain() {
        if (writing != null) return;
        if (queued.isEmpty()) {
            Runnable action = afterPendingWrites != null ? afterPendingWrites : afterSave;
            afterSave = null;
            afterPendingWrites = null;
            if (action != null) action.run();
            return;
        }
        Edit next = queued.values().iterator().next();
        queued.remove(next.index());
        writing = next;
        writer.save(next, error -> {
            boolean current = drafts.get(next.index()) == next;
            if (current && error == null) drafts.remove(next.index());
            if (current && error != null) afterSave = null;
            writing = null;
            if (current) status.accept(next,error);
            drain();
        });
    }
}
