package au.com.gildo.fm;

/** Status of the existing device exchanges displayed on the main screen. */
record ConnectionFlag(Level level, String label) {
    enum Level { IDLE, PENDING, GOOD, FAILED }
    enum Channel {
        GDL("GDL Wi-Fi"), GPS("GPS"), GTI("GTi"), CALIBRATION("Calibration"), CANON("Canon link"), RECORDING("Recording"),
        EVENTS("Canon events"), ZOOM("Zoom");

        final String title;
        Channel(String title) { this.title = title; }
    }

    static final long HELLO_INTERVAL_MS=5_000;
    static final long RECEIVER_WINDOW_MS=2*HELLO_INTERVAL_MS;

    static ConnectionFlag gps(long now,long lastReceiverMs,long fixMs,long packet,double maxAgeMs,boolean simulated) {
        if(!simulated && (lastReceiverMs<0 || now-lastReceiverMs>RECEIVER_WINDOW_MS))
            return failed("No receiver data");
        long age=now-fixMs;
        if(fixMs>=0 && age<=maxAgeMs) return good((simulated?"Simulator":"GPS OK")+" · Fix #"+packet);
        String label=simulated?"Simulator · No fresh fix":"Receiver OK · No fresh fix";
        if(fixMs>=0) label+=" · "+age/1000+" s ago";
        return pending(label);
    }

    static ConnectionFlag idle(String label) { return new ConnectionFlag(Level.IDLE, label); }
    static ConnectionFlag pending(String label) { return new ConnectionFlag(Level.PENDING, label); }
    static ConnectionFlag good(String label) { return new ConnectionFlag(Level.GOOD, label); }
    static ConnectionFlag failed(String label) { return new ConnectionFlag(Level.FAILED, label); }
}
