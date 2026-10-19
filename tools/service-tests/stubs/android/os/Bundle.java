package android.os;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
public final class Bundle implements Parcelable {
    private final Map<String, Object> values = new HashMap<>();
    public static final Parcelable.Creator<Bundle> CREATOR = new Parcelable.Creator<Bundle>() {
        @Override public Bundle createFromParcel(Parcel parcel) { throw new UnsupportedOperationException(); }
        @Override public Bundle[] newArray(int size) { return new Bundle[size]; }
    };
    public void putBundle(String key,Bundle value) { values.put(key,value); }
    public void putParcelableArrayList(String key,ArrayList<? extends Parcelable> value) { values.put(key,value); }
    public void putParcelable(String key, Parcelable value) { values.put(key, value); }
    public void putBoolean(String key, boolean value) { values.put(key, value); }
    public void putLong(String key, long value) { values.put(key, value); }
    public void putInt(String key, int value) { values.put(key, value); }
    public void putString(String key, String value) { values.put(key, value); }
    public int getInt(String key, int fallback) { Object value = values.get(key); return value instanceof Integer ? (Integer) value : fallback; }
    public boolean getBoolean(String key) { return Boolean.TRUE.equals(values.get(key)); }
    public boolean getBoolean(String key, boolean fallback) { Object value = values.get(key); return value instanceof Boolean ? (Boolean) value : fallback; }
    public String getString(String key) { Object value = values.get(key); return value instanceof String ? (String) value : null; }
    public String getString(String key, String fallback) { Object value = values.get(key); return value instanceof String ? (String) value : fallback; }
    public boolean containsKey(String key) { return values.containsKey(key); }
    public Set<String> keySet() { return values.keySet(); }
    public Object get(String key) { return values.get(key); }
    // Approximate host transport size only; actual Android Parcel limits remain enforced on device.
    @Override public void writeToParcel(Parcel parcel,int flags) {
        parcel.addBytes(32);
        for (Map.Entry<String,Object> entry : values.entrySet()) {
            parcel.addBytes(16 + entry.getKey().length() * 2);
            Object value = entry.getValue();
            if (value instanceof Bundle) ((Bundle) value).writeToParcel(parcel,flags);
            else if (value instanceof ArrayList) {
                for (Object item : (ArrayList<?>) value) {
                    if (item instanceof Bundle) ((Bundle) item).writeToParcel(parcel,flags);
                    else parcel.addBytes(16);
                }
            } else if (value instanceof String) parcel.addBytes(8 + ((String) value).length() * 2);
            else parcel.addBytes(8);
        }
    }
}
