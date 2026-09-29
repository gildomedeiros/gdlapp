package dji.v5.ux.sample.showcase.defaultlayout.aiming;

/** CAM3 v2.5: Routine packets, positions, commands and read snapshots stay out of private minimal logs. */
public final class MinimalLogPolicy {
    public static boolean keep(String event, String detail) {
        if (event.equals("readiness") || event.equals("blockers") || event.equals("lora_packet")
                || event.equals("authority_observed") || event.equals("first_command")) return false;
        if (event.equals("telemetry_listener_connected") || event.equals("telemetry_listener_flightMode")
                || event.startsWith("telemetry_retry_timeout_")) return true;
        if (event.startsWith("telemetry_")) {
            if (event.contains("retry_request_") || event.contains("read_ignored_")
                    || event.contains("listener_") || detail.contains("error=none")) return false;
            return !detail.equals("none") && !detail.equals("not_read");
        }
        if (event.startsWith("sdk_callback"))
            return detail.contains("success=false") || detail.contains("error") || detail.contains("failure")
                    || detail.contains("timeout") || detail.contains("disconnected");
        return true;
    }
}
