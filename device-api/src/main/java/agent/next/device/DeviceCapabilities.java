package agent.next.device;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Protocol/feature handshake returned by a connected device implementation. */
public final class DeviceCapabilities {

    private final int protocolVersion;
    private final int apiLevel;
    private final int serviceUid;
    private final int appUid;
    private final boolean supported;
    private final Set<DeviceFeature> features;

    public DeviceCapabilities(int protocolVersion, int apiLevel, int serviceUid, int appUid,
            boolean supported, Set<DeviceFeature> features) {
        Text.requirePositive(protocolVersion, "protocolVersion");
        Text.requirePositive(apiLevel, "apiLevel");
        if (serviceUid < 0 || appUid < 0) {
            throw new IllegalArgumentException("uids must not be negative");
        }
        if (features == null) {
            throw new NullPointerException("features");
        }
        EnumSet<DeviceFeature> copy = EnumSet.noneOf(DeviceFeature.class);
        copy.addAll(features);
        this.protocolVersion = protocolVersion;
        this.apiLevel = apiLevel;
        this.serviceUid = serviceUid;
        this.appUid = appUid;
        this.supported = supported;
        this.features = Collections.unmodifiableSet(copy);
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public int apiLevel() {
        return apiLevel;
    }

    public int serviceUid() {
        return serviceUid;
    }

    public int appUid() {
        return appUid;
    }

    public boolean supported() {
        return supported;
    }

    public Set<DeviceFeature> features() {
        return features;
    }

    public boolean has(DeviceFeature feature) {
        return features.contains(feature);
    }

    @Override
    public String toString() {
        return "DeviceCapabilities{protocol=" + protocolVersion + ", api=" + apiLevel
                + ", supported=" + supported + ", features=" + features + '}';
    }
}
