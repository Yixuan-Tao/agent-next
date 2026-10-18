package agent.next.brain.skill;

import agent.next.device.DeviceException;
import agent.next.device.Frame;

/**
 * Trusted frame source for prompt skills. The model receives the image, but
 * still cannot return coordinates; the frame is evidence, not an action API.
 */
@FunctionalInterface
public interface SkillFrameSource {

    Frame capture() throws DeviceException;
}
