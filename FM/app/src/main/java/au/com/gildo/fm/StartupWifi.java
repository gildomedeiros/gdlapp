package au.com.gildo.fm;

/** Service UI-thread state. The discovery worker also reads useOriginalRoute(). */
final class StartupWifi {
    boolean starting, requesting;
    private boolean discoverHotspot;
    private volatile boolean replacedOriginalRoute;

    boolean canRequest(boolean serviceIdle) { return serviceIdle && !requesting; }
    boolean canUseMount(boolean serviceIdle) { return !starting && canRequest(serviceIdle); }

    void request() {
        requesting=true;
        discoverHotspot=false;
        if(starting) replacedOriginalRoute=true;
    }

    boolean useOriginalRoute() { return !replacedOriginalRoute; }

    void networkChanged(boolean available) {
        requesting=false;
        discoverHotspot=available;
    }

    boolean takeDiscovery(boolean mountReady) {
        if(!mountReady || !discoverHotspot) return false;
        discoverHotspot=false;
        return true;
    }
}
