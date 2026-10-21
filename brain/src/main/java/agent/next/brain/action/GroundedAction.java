package agent.next.brain.action;

import agent.next.brain.ActionDescriptor;
import agent.next.device.NodeBounds;
import agent.next.device.ObservationRef;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * A proposal bound to one task, target, observation version and resolved
 * concrete element. Coordinates and observation ids never come from the model.
 */
public record GroundedAction(
        String taskId,
        String targetPackage,
        String targetToken,
        ObservationRef observation,
        ActionProposal proposal,
        GroundedTarget target,
        ActionDescriptor descriptor) {

    public GroundedAction {
        taskId = text(taskId, "taskId");
        targetPackage = text(targetPackage, "targetPackage");
        targetToken = text(targetToken, "targetToken");
        observation = Objects.requireNonNull(observation, "observation");
        proposal = Objects.requireNonNull(proposal, "proposal");
        target = Objects.requireNonNull(target, "target");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    public sealed interface GroundedTarget permits Node, None {
    }

    public record Node(String ref, NodeBounds bounds) implements GroundedTarget {
        public Node {
            ref = text(ref, "ref");
            bounds = Objects.requireNonNull(bounds, "bounds");
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                throw new IllegalArgumentException("target bounds must be non-empty");
            }
        }
    }

    public record None() implements GroundedTarget {
    }

    /** Length-delimited fields prevent ambiguous concatenation in approval bindings. */
    static String fingerprint(List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            byte[] hash = digest.digest();
            char[] hex = new char[hash.length * 2];
            String digits = "0123456789abcdef";
            for (int i = 0; i < hash.length; i++) {
                int value = hash[i] & 0xff;
                hex[i * 2] = digits.charAt(value >>> 4);
                hex[i * 2 + 1] = digits.charAt(value & 0x0f);
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
