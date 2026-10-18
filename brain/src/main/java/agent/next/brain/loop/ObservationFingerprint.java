package agent.next.brain.loop;

import agent.next.brain.StateFingerprint;
import agent.next.device.Observation;
import agent.next.device.ObservationStatus;
import agent.next.device.UiNode;

/**
 * Builds the brain's comparable state fingerprint from a device observation.
 * The digest intentionally excludes observation ids and timestamps so an
 * unchanged screen compares equal even when it was re-observed.
 */
public final class ObservationFingerprint {

    private ObservationFingerprint() {
    }

    public static StateFingerprint of(Observation observation) {
        String activity = observation.activityName().isBlank() ? "-" : observation.activityName();
        if (observation.status() != ObservationStatus.READY) {
            return new StateFingerprint(observation.packageName(), activity,
                    Hashes.sha256(observation.status().name(), Integer.toString(observation.windowId())),
                    false);
        }
        StringBuilder material = new StringBuilder();
        material.append(observation.packageName()).append('|')
                .append(activity).append('|')
                .append(observation.windowId()).append('|');
        for (UiNode node : observation.nodes()) {
            material.append(node.viewId()).append('|')
                    .append(node.className()).append('|')
                    .append(node.text()).append('|')
                    .append(node.contentDescription()).append('|')
                    .append(node.bounds()).append('|')
                    .append(node.flags()).append('|')
                    .append(node.textTruncated()).append('|');
        }
        return new StateFingerprint(observation.packageName(), activity,
                Hashes.sha256(material.toString()), true);
    }
}
