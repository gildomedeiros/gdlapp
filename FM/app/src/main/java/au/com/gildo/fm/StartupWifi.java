package au.com.gildo.fm;

/** Service UI-thread state. The discovery worker also reads useOriginalRoute(). */
final class StartupWifi {
    boolean starting, requesting;
    private boolean discoverHotspot;
    private volatile boolean replacedOriginalRoute;
    private enum Stage { IDLE, GTI, CANON }
    private volatile Stage stage=Stage.IDLE;
    private volatile long routeRevision;
    private boolean showNextHotspotSearch=true, showProgress;

    boolean canRequest(boolean serviceIdle) { return serviceIdle && !requesting; }
    boolean canUseMount(boolean serviceIdle) { return !starting && canRequest(serviceIdle); }

    void request() {
        resetDiscovery();
        showNextHotspotSearch=true;
        requesting=true;
        discoverHotspot=false;
        if(starting) replacedOriginalRoute=true;
    }

    boolean useOriginalRoute() { return !replacedOriginalRoute; }

    void networkChanged(boolean available) {
        resetDiscovery();
        requesting=false;
        discoverHotspot=available;
    }

    boolean takeDiscovery(boolean mountReady) {
        if(!mountReady || !discoverHotspot) return false;
        discoverHotspot=false;
        stage=Stage.GTI;
        showProgress=showNextHotspotSearch;
        showNextHotspotSearch=false;
        return true;
    }

    long routeRevision() { return routeRevision; }

    boolean startCanon(long route,boolean enabled) {
        if(route!=routeRevision || stage!=Stage.GTI) return false;
        stage=enabled?Stage.CANON:Stage.IDLE;
        return enabled;
    }

    boolean canSearchDevice() { return !requesting && !discoverHotspot && stage==Stage.IDLE; }
    void startDeviceSearch(boolean mount) {
        stage=mount?Stage.GTI:Stage.CANON;
        showProgress=true;
    }
    boolean findingCanon(long route) { return route==routeRevision && stage==Stage.CANON; }

    void finishDiscovery(long route) {
        if(route==routeRevision) stage=Stage.IDLE;
    }

    private void resetDiscovery() {
        routeRevision++;
        stage=Stage.IDLE;
        showProgress=false;
    }

    void hideProgress() { showProgress=false; }

    String progress() {
        if(!showProgress) return "";
        return switch(stage) {
            case GTI -> "Finding GTi…";
            case CANON -> "Finding Canon SX…";
            default -> "";
        };
    }
}
