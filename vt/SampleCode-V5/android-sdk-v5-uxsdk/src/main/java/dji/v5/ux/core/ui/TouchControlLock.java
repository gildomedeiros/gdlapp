package dji.v5.ux.core.ui;
/** VT 3.3: process-local UI lock; owner identity prevents old Activity cleanup unlocking a new screen. */
public final class TouchControlLock {
    private static volatile Object owner;
    private TouchControlLock() { }
    public static synchronized void lock(Object screen) { owner=screen; }
    public static synchronized void unlock(Object screen) { if(owner==screen) owner=null; }
    public static boolean isLocked() { return owner!=null; }
}
