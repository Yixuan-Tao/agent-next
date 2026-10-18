package agent.phone.connection;
final class ConnectionChecks {
    private ConnectionChecks() { }
    static ConnectionState inspect() { throw new UnsupportedOperationException("SDK state probing is outside adapter host tests"); }
}
