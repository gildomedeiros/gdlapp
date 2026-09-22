package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam;

/**
 * Combined velocity command; BODY X is forward, BODY Y is right.
 * DJI velocity-mode roll is X velocity, pitch is Y velocity (not the angle-mode semantics).
 * See Docs/VT_2.8.md for the SDK references and required device axis verification.
 */
public final class AimingMotionCommand {
    private AimingMotionCommand() { }
    public static VirtualStickFlightControlParam build(double yaw,double forward) {
        if(!Double.isFinite(forward) || Math.abs(forward)>ComeToMeSettings.MAX_SPEED)
            throw new IllegalArgumentException("Forward velocity limit");
        VirtualStickFlightControlParam command=YawOnlyCommand.build(yaw);
        command.setRoll(forward);
        // YawOnlyCommand explicitly retains pitch (lateral) and vertical velocity at zero.
        return command;
    }
}
