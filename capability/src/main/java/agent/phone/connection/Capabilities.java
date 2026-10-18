package agent.phone.connection;

public final class Capabilities {
    public final int protocol;
    public final int api;
    public final int serviceUid;
    public final int appUid;
    public final boolean supported;
    public final boolean previewMirror;

    Capabilities(int protocol, int api, int serviceUid, int appUid, boolean supported,
            boolean previewMirror) {
        this.protocol = protocol;
        this.api = api;
        this.serviceUid = serviceUid;
        this.appUid = appUid;
        this.supported = supported;
        this.previewMirror = previewMirror;
    }
}
