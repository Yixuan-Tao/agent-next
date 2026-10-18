package agent.phone.connection;

public final class Capabilities {
    public final int protocol;
    public final int api;
    public final int serviceUid;
    public final int appUid;
    public final boolean supported;

    Capabilities(int protocol, int api, int serviceUid, int appUid, boolean supported) {
        this.protocol = protocol;
        this.api = api;
        this.serviceUid = serviceUid;
        this.appUid = appUid;
        this.supported = supported;
    }

}
