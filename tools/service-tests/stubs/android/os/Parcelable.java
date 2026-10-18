package android.os;
public interface Parcelable {
    int PARCELABLE_WRITE_RETURN_VALUE = 1;
    void writeToParcel(Parcel parcel, int flags);
    interface Creator<T> { T createFromParcel(Parcel parcel); T[] newArray(int size); }
}
