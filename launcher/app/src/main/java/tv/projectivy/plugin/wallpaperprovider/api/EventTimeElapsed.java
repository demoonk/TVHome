package tv.projectivy.plugin.wallpaperprovider.api;

import android.os.Parcel;
import android.os.Parcelable;

/** The plugin may refresh its wallpapers; this event is all the launcher sends with it. */
public final class EventTimeElapsed extends Event {
    public EventTimeElapsed() {}

    EventTimeElapsed(Parcel in) {} // the event carries nothing after its type Int

    @Override public void writeToParcel(Parcel dest, int flags) { dest.writeInt(EVENT_TIME_ELAPSED); }

    @Override public int describeContents() { return 0; }

    public static final Parcelable.Creator<EventTimeElapsed> CREATOR = new Parcelable.Creator<EventTimeElapsed>() {
        @Override public EventTimeElapsed createFromParcel(Parcel in) { return new EventTimeElapsed(in); }
        @Override public EventTimeElapsed[] newArray(int size) { return new EventTimeElapsed[size]; }
    };
}
