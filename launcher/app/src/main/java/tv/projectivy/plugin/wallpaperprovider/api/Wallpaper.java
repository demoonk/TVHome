package tv.projectivy.plugin.wallpaperprovider.api;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A wallpaper offered by a Projectivy wallpaper-provider plugin. The Java copy of the plugin's
 * Kotlin {@code @Parcelize} class: the read and write order below is the wire format, so it must
 * stay exactly in the plugin's field order — uri, type, displayMode, then the four strings.
 * Only VIDEO wallpapers are played here; the other types are read and left unused.
 */
public final class Wallpaper implements Parcelable {
    public static final int TYPE_IMAGE = 0, TYPE_DRAWABLE = 1, TYPE_ANIMATED_DRAWABLE = 2,
            TYPE_LOTTIE = 3, TYPE_VIDEO = 4, TYPE_COLOR = 5;
    public static final int DISPLAY_DEFAULT = 0, DISPLAY_CROP = 1, DISPLAY_STRETCH = 2,
            DISPLAY_BLUR = 3, DISPLAY_FIT_CENTER = 4, DISPLAY_FIT_CENTER_BLURRED_BG = 5;

    public String uri;
    public int type;
    public int displayMode;
    public String title;
    public String source;
    public String author;
    public String actionUri;

    public Wallpaper() {}

    private Wallpaper(Parcel in) {
        uri = in.readString();
        type = in.readInt();
        displayMode = in.readInt();
        title = in.readString();
        source = in.readString();
        author = in.readString();
        actionUri = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(uri);
        dest.writeInt(type);
        dest.writeInt(displayMode);
        dest.writeString(title);
        dest.writeString(source);
        dest.writeString(author);
        dest.writeString(actionUri);
    }

    @Override public int describeContents() { return 0; }

    public static final Parcelable.Creator<Wallpaper> CREATOR = new Parcelable.Creator<Wallpaper>() {
        @Override public Wallpaper createFromParcel(Parcel in) { return new Wallpaper(in); }
        @Override public Wallpaper[] newArray(int size) { return new Wallpaper[size]; }
    };
}
