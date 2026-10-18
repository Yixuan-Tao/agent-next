package android.content;
import android.os.Parcel;
import android.os.Parcelable;
public final class ComponentName implements Parcelable {
    private final String packageName;
    private final String className;
    public ComponentName(String packageName, String className) { this.packageName = packageName; this.className = className; }
    public String getPackageName() { return packageName; }
    public String getClassName() { return className; }
    @Override public void writeToParcel(Parcel parcel, int flags) { throw new UnsupportedOperationException(); }
}
