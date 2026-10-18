package agent.phone.connection;

/** Opens a driver already wired to the supplied state listener. */
public interface ConnectionDriverFactory {

    ConnectionDriver open(ConnectionDriver.Listener listener);
}
