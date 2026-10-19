package agent.next.app.skill;

import android.util.Log;

import agent.next.brain.ActionDescriptor;
import agent.next.brain.ActionKind;
import agent.next.brain.RiskClass;
import agent.next.brain.loop.LoopAction;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.Frame;
import agent.next.device.InputReceipt;
import agent.next.device.InputStatus;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.Point;
import agent.next.device.TargetHandle;
import agent.next.device.UiNode;

import java.time.Instant;

/** Executes one visual calculator sequence inside a single governed action. */
public final class CalculatorSequenceAction implements LoopAction {

    private static final String TAG = "AgentNextRuntime";
    private final CalculatorExpression.Calculation calculation;
    private final VisualButtonLocator locator;

    public CalculatorSequenceAction(CalculatorExpression.Calculation calculation,
            VisualButtonLocator locator) {
        this.calculation = calculation;
        this.locator = locator;
    }

    @Override
    public String id() {
        return "calculator.sequence";
    }

    @Override
    public String description() {
        return "visual calculator sequence: " + calculation.expression();
    }

    @Override
    public ActionKind kind() {
        return ActionKind.NAVIGATE;
    }

    @Override
    public RiskClass risk() {
        return RiskClass.LOW;
    }

    @Override
    public String payloadHash() {
        return Integer.toHexString(calculation.expression().hashCode());
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException {
        for (String label : calculation.buttons()) {
            try {
                Observation current = api.observations().observe(
                        target, ObservationOptions.defaults());
                Point point = nodePoint(current, label);
                if (point == null) {
                    Frame frame = api.observations().captureFrame(target);
                    point = locator.locate(frame, label);
                }
                Log.i(TAG, "calculator sequence tap label=" + label
                        + " point=" + point.x() + "," + point.y());
                api.input().tap(target, current, point);
                Thread.sleep(label.equals("=") ? 1000L : 300L);
            } catch (java.io.IOException failure) {
                throw new DeviceException(DeviceException.Code.INTERNAL,
                        "calculator visual locate failed for " + label, failure);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DeviceException(DeviceException.Code.INTERNAL,
                        "calculator sequence interrupted", interrupted);
            }
        }
        return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
    }

    private static Point nodePoint(Observation observation, String label) {
        String wanted = switch (label) {
            case "C（清除）" -> "清除";
            case "×" -> "乘";
            case "=" -> "等于";
            default -> label;
        };
        for (UiNode node : observation.nodes()) {
            if (!node.hasFlag(agent.next.device.NodeFlag.CLICKABLE)
                    || node.hasFlag(agent.next.device.NodeFlag.EDITABLE)) {
                continue;
            }
            String text = node.text().trim();
            String desc = node.contentDescription().trim();
            boolean match = text.equals(label) || desc.equals(wanted)
                    || (label.equals("C（清除）") && desc.contains("清除"))
                    || (label.equals("×") && (desc.contains("乘") || text.equals("×")))
                    || (label.equals("=") && (desc.contains("等于") || text.equals("=")));
            if (match && node.bounds().width() > 0 && node.bounds().height() > 0) {
                return new Point(node.bounds().centerX(), node.bounds().centerY());
            }
        }
        return null;
    }

    @Override
    public ActionDescriptor descriptor(String targetApp) {
        return new ActionDescriptor(id(), description(), kind(), risk(), targetApp,
                payloadHash(), java.util.Set.of());
    }
}
