// this code probably doesn't work yet
// this commit is just here so that others can work on this code right now

// Written by AcaBots FTC team 24689 for the 2025-26 DECODE Season

/*
* **THIS COMMENT IS REALLY OUTDATED DON'T RELY ON IT**
* **THIS COMMENT IS REALLY OUTDATED DON'T RELY ON IT**
* **THIS COMMENT IS REALLY OUTDATED DON'T RELY ON IT**
* **THIS COMMENT IS REALLY OUTDATED DON'T RELY ON IT**
*
-------------------- CONTROL SCHEME - CONTROLLER 1 --------------------
Buttons:
    A: Toggle intake, transfer, and riser belts
    START: Reset IMU
    BACK: Reverse intake, transfer, and riser belts + turret intake kicker wheel


D-Pad:
    UP: Close/lobbing preset
    DOWN: Far launch zone preset
    RIGHT: Middle launch zone preset, low angle
    LEFT: Middle launch zone preset, high angle

Triggers:
    RT: Turret rotate right
    LT: Turret rotate left

Shoulder Buttons:
    RB: Turret flywheel
    LB: Turret intake kicker wheel

Joysticks:
    Right: Relative Chassis Rotation
    Left: Absolute Chassis Strafe based on orientation when START button is pressed

-------------------- CONTROL SCHEME - CONTROLLER 2 --------------------
Same as controller 1, except turret rotation is disabled
 */

package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;
import com.qualcomm.robotcore.hardware.CRServo;
import com.qualcomm.robotcore.hardware.DcMotorSimple;
import com.qualcomm.robotcore.hardware.IMU;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.hardware.DcMotor;
//import java.util.concurrent.*;

@TeleOp // Defines class as a teleOP routine
public class TestOP extends LinearOpMode {
    // TODO change these placeholders
    // Move more forward to get to nectar position.
    // Move more backward to get to pollen position.
    private static final double FLYWHEEL_PRESET_POLLEN_ROTATION = 0;
    private static final double FLYWHEEL_PRESET_NECTAR_ROTATION = 20;
    public static final double FLYWHEEL_AT_TARGET_THRESHOLD = 0.01;
    /*
     * Motors:
     * - driveMotor1/2/3/4: 312 RPM motors
     * - driveMotor arrangement:
     * - 1====================2
     * - |                    |
     * - 3====================4
     * - intakeMotor: 1150 RPM motor for intake
     * - intakeToShooterMotor: Motor used to feed the flywheel
     * - spinFlywheelMotor: 6000 RPM motor used for spinning the flywheel
     * Servos:
     * - flywheelServo: CRServo used to adjust how big the flywheel is (the black 3d printed part)
     */
    private DcMotor spinFlywheelMotor;
    private DcMotor spinIntakeMotor;
    private DcMotor driveMotor0;
    private DcMotor driveMotor1;
    private DcMotor driveMotor2;
    private DcMotor driveMotor3;
    private DcMotor[] driveMotors;
    private DcMotor intakeToShooterMotor;
    private CRServo flywheelServo;
    private double[] wheelPowers;
    private double shooterPower;
    private double rotationOffset;
    private double targetRotationOffset;
    private boolean rotationLimitsApplied;
    private double flywheelServoPower;
    private long lastLoop;

    FlywheelServoControlMode currentFlywheelServoControlMode;
    private boolean spinIntakeToggle;
    private boolean spinIntakeDirectionToggle;
    private boolean shooterToggle;
    private boolean intakeToShooterDirectionToggle;

    enum FlywheelServoControlMode {
        USER,
        PRESET
    }

    /**
     * Sets the same drive power to all 4 motors.
     *
     * @param power The power to apply
     */
    private void applyWheelPower(double power) {
        for (DcMotor motor : driveMotors) {
            motor.setPower(power);
        }
    }

    /**
     * Sets four different drive powers to certain motors as described by wheelFlag.
     *
     * @param wheelPowers A 4-item array of wheel power values
     * @param wheelFlag A bitset describing which motors to affect. For example, 0b1011 applies wheelPowers[0], wheelPowers[2], and wheelPowers[3] to motors 1, 3, and 4, while keeping motor 2's power the same.
     */
    private void applyWheelPower(double[] wheelPowers, byte wheelFlag) {
        /*
         * wheelFlag = 0b1100 applies to both front wheels given driveMotors[0] = driveMotor0 and driveMotors[1] = driveMotor1
         * wheelFlag = 0b0010 applies to only the bottom left wheel given driveMotors[0] = driveMotor2 and driveMotors[1] = driveMotor3
         */
        if ((wheelFlag & 0x8) > 0) driveMotors[0].setPower(wheelPowers[0]);
        if ((wheelFlag & 0x4) > 0) driveMotors[1].setPower(wheelPowers[1]);
        if ((wheelFlag & 0x2) > 0) driveMotors[2].setPower(wheelPowers[2]);
        if ((wheelFlag & 0x1) > 0) driveMotors[3].setPower(wheelPowers[3]);
    }

    private void setWheelPower(double[] wheelPowers, double turn, double forward, double strafe) {
        wheelPowers[0] = forward + strafe + turn;
        wheelPowers[1] = forward - strafe - turn;
        wheelPowers[2] = forward - strafe + turn;
        wheelPowers[3] = forward + strafe - turn;

        double max = Math.max(1.0, Math.max(Math.max(wheelPowers[0], wheelPowers[1]), Math.max(wheelPowers[2], wheelPowers[3])));
        for (byte i = 0; i < 4; i++) {
            wheelPowers[i] /= max;
        }
    }

    private double setFlywheelServoPower(boolean pollenPresetPressed, boolean nectarPresetPressed,  boolean moveLeftHeld, boolean moveRightHeld) {
        float netFlywheelServoPower = 0f;

        if (currentFlywheelServoControlMode == FlywheelServoControlMode.USER) {
            // Move more forward to get to nectar position.
            // Move more backward to get to pollen position.
            // If both buttons are held, netFlywheelServoPower == 0.
            if (moveRightHeld && (rotationOffset < FLYWHEEL_PRESET_NECTAR_ROTATION || !rotationLimitsApplied)) netFlywheelServoPower += 0.5f;
            if (moveLeftHeld && (rotationOffset > FLYWHEEL_PRESET_POLLEN_ROTATION || !rotationLimitsApplied)) netFlywheelServoPower -= 0.5f;

            if (pollenPresetPressed) {
                currentFlywheelServoControlMode = FlywheelServoControlMode.PRESET;
                targetRotationOffset = FLYWHEEL_PRESET_POLLEN_ROTATION;
            }
            if (nectarPresetPressed) {
                currentFlywheelServoControlMode = FlywheelServoControlMode.PRESET;
                targetRotationOffset = FLYWHEEL_PRESET_NECTAR_ROTATION;
            }
        } else if (currentFlywheelServoControlMode == FlywheelServoControlMode.PRESET) {
            if (rotationOffset < targetRotationOffset) {
                netFlywheelServoPower = 0.5f;
            } else if (rotationOffset > targetRotationOffset) {
                netFlywheelServoPower = -0.5f;
            }
            if ((targetRotationOffset - rotationOffset) < FLYWHEEL_AT_TARGET_THRESHOLD) currentFlywheelServoControlMode = FlywheelServoControlMode.USER;
        }

        return netFlywheelServoPower;
    }

    private void applyFlywheelServoPower(double netFlywheelServoPower) {
        if (netFlywheelServoPower < 0) flywheelServo.setDirection(CRServo.Direction.REVERSE);
        else flywheelServo.setDirection(CRServo.Direction.FORWARD);
        flywheelServoPower = netFlywheelServoPower;
        flywheelServo.setPower(Math.abs(netFlywheelServoPower));
    }

    private double setShooterPower(boolean increaseSpeedHeld, boolean decreaseSpeedHeld) {
        double netShooterPower = spinFlywheelMotor.getPower();
        if (increaseSpeedHeld) netShooterPower = Math.max(0, Math.min(1, netShooterPower + 0.1));
        if (decreaseSpeedHeld) netShooterPower = Math.max(0, Math.min(1, netShooterPower - 0.1));

        return netShooterPower;
    }

    private void applyShooterPower(double netShooterPower) {
        if (shooterToggle) spinFlywheelMotor.setPower(netShooterPower);
        else spinFlywheelMotor.setPower(0);
    }

    @Override
    public void runOpMode() throws InterruptedException { // Main execution function
        // init
        intakeToShooterMotor = hardwareMap.dcMotor.get("intakeToShooterMotor");
        spinFlywheelMotor = hardwareMap.dcMotor.get("spinFlywheelMotor");
        spinIntakeMotor = hardwareMap.dcMotor.get("spinIntakeMotor");
        driveMotor0 = hardwareMap.dcMotor.get("driveMotor0");
        driveMotor1 = hardwareMap.dcMotor.get("driveMotor1");
        driveMotor2 = hardwareMap.dcMotor.get("driveMotor2");
        driveMotor3 = hardwareMap.dcMotor.get("driveMotor3");

        driveMotors = new DcMotor[]{driveMotor0, driveMotor1, driveMotor2, driveMotor3};

        flywheelServo = hardwareMap.crservo.get("flywheelServo");

        wheelPowers = new double[]{ 0, 0, 0, 0 };
        shooterPower = 0.5;

        lastLoop = System.nanoTime() / 1000000;
        rotationOffset = 0;
        flywheelServoPower = 0;
        rotationLimitsApplied = true;
        currentFlywheelServoControlMode = FlywheelServoControlMode.USER;

        spinIntakeToggle = false;
        spinIntakeDirectionToggle = true;
        shooterToggle = false;
        intakeToShooterDirectionToggle = false;

        /*ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        Runnable clockTask = this::clockTask;*/

        driveMotor0.setDirection(DcMotor.Direction.REVERSE); // Left motors
        driveMotor2.setDirection(DcMotor.Direction.REVERSE);
        driveMotor1.setDirection(DcMotor.Direction.FORWARD); // Right motors
        driveMotor3.setDirection(DcMotor.Direction.FORWARD);
        spinFlywheelMotor.setDirection(DcMotorSimple.Direction.REVERSE);

        telemetry.addData("Status", "Initialized");
        telemetry.addData("Estimated flywheel servo rotation", "%.2f", rotationOffset);
        telemetry.update();

        waitForStart();

        if (isStopRequested()) return;

        // CONTROL LOOP
        while (opModeIsActive()) {
            long currentLoop = System.nanoTime() / 1000000;
            long loopTime = currentLoop - lastLoop;
            rotationOffset += loopTime * flywheelServoPower;

            double turn = gamepad1.right_stick_x;
            double forward = -gamepad1.left_stick_y;
            double strafe = gamepad1.left_stick_x;

            setWheelPower(wheelPowers, turn, forward, strafe); // This function mutates the wheelPowers array
            applyWheelPower(wheelPowers, (byte) 0b1111);

            // TODO REMOVE THIS ONCE THE STOPS ARE TUNED
            if (gamepad1.leftBumperWasPressed()) rotationLimitsApplied = !rotationLimitsApplied;
            applyFlywheelServoPower(setFlywheelServoPower(/*gamepad1.dpadUpWasPressed()*/ false, /*gamepad1.dpadDownWasPressed()*/ false, gamepad1.dpad_left, gamepad1.dpad_right));

            if (gamepad1.bWasPressed()) shooterToggle = !shooterToggle;
            applyShooterPower(setShooterPower(gamepad1.dpadUpWasPressed(), gamepad1.dpadDownWasPressed()));

            if (gamepad1.rightBumperWasPressed()) spinIntakeToggle = !spinIntakeToggle;
            if (gamepad1.rightTriggerWasPressed()) spinIntakeDirectionToggle = !spinIntakeDirectionToggle;
            if (spinIntakeToggle) {
                if (spinIntakeDirectionToggle) spinIntakeMotor.setDirection(DcMotorSimple.Direction.FORWARD);
                else spinIntakeMotor.setDirection(DcMotorSimple.Direction.REVERSE);
                spinIntakeMotor.setPower(1);
            } else {
                spinIntakeMotor.setPower(0);
            }

            if (gamepad1.left_bumper || (gamepad1.left_trigger > 0)) {
                intakeToShooterMotor.setPower(1);
                if (gamepad1.left_bumper) intakeToShooterMotor.setDirection(DcMotorSimple.Direction.FORWARD);
                else if (gamepad1.left_trigger > 0) intakeToShooterMotor.setDirection(DcMotorSimple.Direction.REVERSE);
            } else {
                intakeToShooterMotor.setPower(0);
            }

            if (gamepad1.xWasPressed()) {
                intakeToShooterDirectionToggle = !intakeToShooterDirectionToggle;
                if (intakeToShooterDirectionToggle) intakeToShooterMotor.setDirection(DcMotorSimple.Direction.FORWARD);
                else intakeToShooterMotor.setDirection(DcMotorSimple.Direction.REVERSE);
            }

            telemetry.addData("Status", "the freshman bot held by rubber bands");
            telemetry.addData("Front wheels power L/R", "%.2f/%.2f", wheelPowers[0], wheelPowers[1]);
            telemetry.addData("Back wheels power L/R", "%.2f/%.2f", wheelPowers[2], wheelPowers[3]);
            telemetry.addData("Shooter power", spinFlywheelMotor.getPower());
            telemetry.addData("Intake on?", spinIntakeToggle ? "Yes": "No");
            telemetry.addData("Estimated flywheel servo rotation", "%.2f", rotationOffset);
            telemetry.addData("Flywheel servo control mode", currentFlywheelServoControlMode == FlywheelServoControlMode.USER ? "User" : "Preset");
            telemetry.addData("Flywheel servo power",flywheelServo.getPower());
            telemetry.update();

            lastLoop = System.nanoTime() / 1000000;
        }
    }
}