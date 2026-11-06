package tv.projectivy.plugin.wallpaperprovider.api;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Base of the events a wallpaper-provider client answers the plugin with. The wire always starts
 * with the event type Int, followed by that event's own fields. The launcher is a client and only
 * ever sends {@link EventTimeElapsed}, so this CREATOR — which the generated AIDL stub references
 * — decodes that one event and rejects everything else rather than guessing at a layout.
 */
public abstract class Event implements Parcelable {
    public static final int EVENT_TIME_ELAPSED = 1;

    public static final Parcelable.Creator<Event> CREATOR = new Parcelable.Creator<Event>() {
        @Override public Event createFromParcel(Parcel in) {
            int type = in.readInt();
            if (type == EVENT_TIME_ELAPSED) return new EventTimeElapsed(in);
            throw new IllegalArgumentException("unknown wallpaper provider event " + type);
        }
        @Override public Event[] newArray(int size) { return new Event[size]; }
    };
}
