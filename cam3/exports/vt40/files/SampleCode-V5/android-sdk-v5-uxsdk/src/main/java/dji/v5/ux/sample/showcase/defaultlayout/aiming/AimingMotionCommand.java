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
        return build(yaw,forward,0);
    }
    public static VirtualStickFlightControlParam build(double yaw,double forward,double right) {
        if(!Double.isFinite(right)||Math.abs(right)>ComeToMeSettings.HARD_MAX_SPEED||
                Math.hypot(forward,right)>Math.max(ComeToMeSettings.HARD_MAX_SPEED,RetreatSettings.MAX_SPEED))throw new IllegalArgumentException("Combined velocity limit");
        if(!Double.isFinite(forward) || (forward>ComeToMeSettings.HARD_MAX_SPEED || -forward>Math.max(ComeToMeSettings.HARD_MAX_SPEED,RetreatSettings.MAX_SPEED)))
            throw new IllegalArgumentException("Forward velocity limit");
        VirtualStickFlightControlParam command=YawOnlyCommand.build(yaw);
        command.setRoll(forward);
        command.setPitch(right);
        // BODY X forward / BODY Y right; vertical velocity remains explicitly zero.
        return command;
    }
}
