package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import dji.sdk.keyvalue.value.flightcontroller.*;

/** CAM3 v2.0: The only aiming command factory; deliberately exposes no translation inputs. */
public final class YawOnlyCommand {
    private YawOnlyCommand() { }
    public static VirtualStickFlightControlParam build(double rate) {
        if (!Double.isFinite(rate) || Math.abs(rate) > YawAimingMath.MAX_RATE) throw new IllegalArgumentException("Yaw limit");
        VirtualStickFlightControlParam command = new VirtualStickFlightControlParam();
        command.setRollPitchCoordinateSystem(FlightCoordinateSystem.BODY);
        command.setRollPitchControlMode(RollPitchControlMode.VELOCITY);
        command.setVerticalControlMode(VerticalControlMode.VELOCITY);
        command.setYawControlMode(YawControlMode.ANGULAR_VELOCITY);
        command.setRoll(0.0); command.setPitch(0.0); command.setVerticalThrottle(0.0);
        command.setYaw(rate);
        return command;
    }
}
