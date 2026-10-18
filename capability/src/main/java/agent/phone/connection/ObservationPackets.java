package agent.phone.connection;

import android.os.Bundle;
import android.os.Parcel;
import agent.phone.executor.ObservationBudget;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/** Fixed Android-built-in transport; validate every field before publishing immutable evidence. */
public final class ObservationPackets {
    private ObservationPackets() { }
    public static Bundle encode(TargetObservation o) {
        Bundle b = new Bundle();
        b.putBundle("target",TargetPackets.encode(o.target));
        b.putString("observation_id",o.observationId); b.putLong("sequence",o.sequence);
        b.putLong("started_nanos",o.startedNanos); b.putLong("ended_nanos",o.endedNanos);
        b.putString("status",o.status.name()); b.putInt("focused_window_id",o.focusedWindowId);
        b.putInt("lookup_count",o.lookupCount); b.putBoolean("truncated",o.truncated);
        b.putBundle("window_bounds",bounds(o.windowBounds));
        ArrayList<Bundle> nodes = new ArrayList<>();
        for (TargetObservation.Node n : o.nodes) {
            Bundle node = new Bundle();
            node.putString("ref",n.ref); node.putInt("index",n.index); node.putInt("window_id",n.windowId);
            node.putInt("parent",n.parent); node.putBundle("bounds",bounds(n.bounds));
            node.putString("text",n.text); node.putString("description",n.description);
            node.putString("resource_id",n.resourceId); node.putString("class_name",n.className);
            node.putBoolean("clickable",n.clickable); node.putBoolean("editable",n.editable);
            node.putBoolean("enabled",n.enabled); node.putBoolean("password",n.password);
            node.putBoolean("visible",n.visible); node.putBoolean("focused",n.focused);
            node.putBoolean("scrollable",n.scrollable); node.putBoolean("text_truncated",n.textTruncated);
            nodes.add(node);
        }
        int nodeCount = nodes.size();
        while (true) {
            b.putParcelableArrayList("nodes", new ArrayList<>(nodes.subList(0, nodeCount)));
            b.putBoolean("truncated", o.truncated || nodeCount < nodes.size());
            try {
                requireSize(b);
                return b;
            } catch (IllegalArgumentException tooLarge) {
                if (nodeCount == 0) throw tooLarge;
                nodeCount = Math.max(0, nodeCount - Math.max(1, nodeCount / 8));
            }
        }
    }
    public static TargetObservation decode(Bundle b,TargetSession expected,long previousSequence) {
        requireSize(b);
        keys(b,"target","observation_id","sequence","started_nanos","ended_nanos","status",
                "focused_window_id","lookup_count","truncated","window_bounds","nodes");
        if (expected == null || !(b.get("target") instanceof Bundle)) throw malformed();
        TargetSession target = TargetPackets.decode((Bundle) b.get("target"),expected.display,expected.taskId,expected.packageName);
        if (!target.sameIdentity(expected)) throw malformed();
        Object raw = b.get("nodes");
        if (!(raw instanceof ArrayList) || ((ArrayList<?>) raw).size() > ObservationBudget.MAX_NODES) throw malformed();
        ArrayList<TargetObservation.Node> nodes = new ArrayList<>();
        for (Object item : (ArrayList<?>) raw) {
            if (!(item instanceof Bundle)) throw malformed();
            Bundle n = (Bundle) item;
            keys(n,"ref","index","window_id","parent","bounds","text","description","resource_id","class_name",
                    "clickable","editable","enabled","password","visible","focused","scrollable","text_truncated");
            nodes.add(new TargetObservation.Node(string(n,"ref"),integer(n,"index"),integer(n,"window_id"),integer(n,"parent"),
                    readBounds(n.get("bounds"),false),string(n,"text"),string(n,"description"),string(n,"resource_id"),
                    string(n,"class_name"),bool(n,"clickable"),bool(n,"editable"),bool(n,"enabled"),bool(n,"password"),
                    bool(n,"visible"),bool(n,"focused"),bool(n,"scrollable"),bool(n,"text_truncated")));
        }
        TargetObservation result = new TargetObservation(target,string(b,"observation_id"),number(b,"sequence"),
                number(b,"started_nanos"),number(b,"ended_nanos"),
                TargetObservation.Status.valueOf(string(b,"status")),integer(b,"focused_window_id"),
                readBounds(b.get("window_bounds"),true),integer(b,"lookup_count"),bool(b,"truncated"),nodes);
        result.requireFresh(expected,previousSequence);
        return result;
    }
    public static void requireSize(Bundle b) {
        if (b == null) throw malformed();
        Parcel parcel = Parcel.obtain();
        try {
            b.writeToParcel(parcel,0);
            if (parcel.dataSize() > ObservationBudget.MAX_PACKET_BYTES) throw malformed();
        } finally { parcel.recycle(); }
    }
    private static Bundle bounds(TargetObservation.Bounds bounds) {
        if (bounds == null) return null;
        Bundle b = new Bundle(); b.putInt("left",bounds.left); b.putInt("top",bounds.top);
        b.putInt("right",bounds.right); b.putInt("bottom",bounds.bottom); return b;
    }
    private static TargetObservation.Bounds readBounds(Object value,boolean nullable) {
        if (value == null && nullable) return null;
        if (!(value instanceof Bundle)) throw malformed();
        Bundle b = (Bundle) value; keys(b,"left","top","right","bottom");
        return new TargetObservation.Bounds(integer(b,"left"),integer(b,"top"),integer(b,"right"),integer(b,"bottom"));
    }
    private static void keys(Bundle b,String... expected) {
        if (!b.keySet().equals(new HashSet<>(Arrays.asList(expected)))) throw malformed();
    }
    private static String string(Bundle b,String key) { Object v = b.get(key); if (!(v instanceof String)) throw malformed(); return (String) v; }
    private static int integer(Bundle b,String key) { Object v = b.get(key); if (!(v instanceof Integer)) throw malformed(); return (Integer) v; }
    private static long number(Bundle b,String key) { Object v = b.get(key); if (!(v instanceof Long)) throw malformed(); return (Long) v; }
    private static boolean bool(Bundle b,String key) { Object v = b.get(key); if (!(v instanceof Boolean)) throw malformed(); return (Boolean) v; }
    private static IllegalArgumentException malformed() { return new IllegalArgumentException("Malformed target observation packet"); }
}
