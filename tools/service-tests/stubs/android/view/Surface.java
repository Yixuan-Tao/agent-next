package android.view;
import android.os.Parcel;
import android.os.Parcelable;
public final class Surface implements Parcelable {
    public static final Parcelable.Creator<Surface> CREATOR = new Parcelable.Creator<Surface>() {
        @Override public Surface createFromParcel(Parcel parcel) { return new Surface(); }
        @Override public Surface[] newArray(int size) { return new Surface[size]; }
    };
    public boolean isValid() { return true; }
    @Override public void writeToParcel(Parcel parcel, int flags) { throw new UnsupportedOperationException(); }
}
