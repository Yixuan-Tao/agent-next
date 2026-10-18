package agent.phone.executor;

import java.util.UUID;

/** Serialized ownership contract; Android allocation and inspection live in the adapter. */
public final class DisplayLifecycle {
    public enum Status { ACTIVE, INVALID, RELEASED, CLEANUP_FAILED }
    public interface Factory { Resource allocate() throws Exception; int expectedFlags() throws Exception; }
    public interface Resource { void open() throws Exception; Info inspect() throws Exception; void close() throws Exception; }
    public static final class Info {
        public final int displayId, ownerUid, width, height, densityDpi, flags;
        public final String uniqueId, name;
        public Info(int id, String unique, int uid, int width, int height, int density, int flags, String name) {
            displayId = id;
            uniqueId = unique;
            ownerUid = uid;
            this.width = width;
            this.height = height;
            densityDpi = density;
            this.flags = flags;
            this.name = name;
        }
    }
    public static final class Session {
        public final String token;
        public final long generation;
        public final Info info;
        public final Status status;
        Session(String token, long generation, Info info, Status status) {
            this.token = token;
            this.generation = generation;
            this.info = info;
            this.status = status;
        }
    }
    private final Factory factory;
    private Resource resource;
    private Session session;
    private long generation;
    private boolean closed;

    public DisplayLifecycle(Factory factory) { this.factory = factory; }

    public synchronized Session create() throws Exception {
        if (closed) throw new IllegalStateException("Display lifecycle is closed");
        if (resource != null) {
            if (session == null || session.status != Status.ACTIVE) {
                throw new IllegalStateException("Previous display requires explicit cleanup");
            }
            Session current = check(session.token, session.generation);
            if (current.status != Status.ACTIVE) throw new IllegalStateException("Display session is invalid");
            return current;
        }
        resource = factory.allocate();
        session = new Session(UUID.randomUUID().toString(), ++generation, null, Status.INVALID);
        try {
            resource.open();
            Info info = resource.inspect();
            requireInfo(info, null);
            session = new Session(session.token, session.generation, info, Status.ACTIVE);
            return session;
        } catch (Exception failure) {
            try { releaseResource(); }
            catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    public synchronized Session check(String token, long expectedGeneration) throws Exception {
        requireIdentity(token, expectedGeneration);
        if (closed) throw new IllegalStateException("Display lifecycle is closed");
        if (session.status != Status.ACTIVE) return session;
        try { requireInfo(resource.inspect(), session.info); }
        catch (Exception invalid) { session = status(Status.INVALID); }
        return session;
    }

    public synchronized Session release(String token, long expectedGeneration) {
        requireIdentity(token, expectedGeneration);
        if (session.status == Status.RELEASED) return session;
        try { releaseResource(); }
        catch (Exception failure) { session = status(Status.CLEANUP_FAILED); }
        return session;
    }

    public synchronized void close() throws Exception {
        closed = true;
        if (resource != null) releaseResource();
    }

    private void releaseResource() throws Exception {
        // Retain ownership on failure: a new allocation cannot hide uncertain cleanup.
        try { resource.close(); }
        catch (Exception failure) { session = status(Status.CLEANUP_FAILED); throw failure; }
        resource = null;
        session = status(Status.RELEASED);
    }

    private Session status(Status status) {
        return new Session(session.token, session.generation, session.info, status);
    }

    private void requireIdentity(String token, long expectedGeneration) {
        if (session == null || token == null || !token.equals(session.token)
                || expectedGeneration != session.generation) {
            throw new SecurityException("Display session identity does not match");
        }
    }

    private void requireInfo(Info info, Info expected) throws Exception {
        if (info == null || info.displayId <= 0 || info.ownerUid != 2000
                || info.uniqueId == null || info.uniqueId.isEmpty() || !"agent-phone".equals(info.name)
                || info.width != 720 || info.height != 1280 || info.densityDpi != 240
                || info.flags != factory.expectedFlags()
                || expected != null && (info.displayId != expected.displayId
                || !info.uniqueId.equals(expected.uniqueId))) {
            throw new IllegalStateException("Display metadata does not match the fixed session");
        }
    }
}
