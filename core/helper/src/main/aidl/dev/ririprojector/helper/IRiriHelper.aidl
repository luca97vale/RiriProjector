// Second-screen shell helper (ADR-014). Every method checks that the caller is the app's uid.
package dev.ririprojector.helper;

import android.view.Surface;
import dev.ririprojector.helper.IRiriHelperListener;

interface IRiriHelper {
    /** HelperService.VERSION; the app restarts a helper from another build. */
    int version();

    /** The APK the helper runs from (CLASSPATH); differs after an app update. */
    String apkPath();

    /** Creates a virtual display on surface (may be null until a link attaches one); returns its id or -1. */
    int createDisplay(in Surface surface, int width, int height, int dpi, int fps);

    /** Swaps the display's Surface (null detaches it between links, ADR-010). */
    void setSurface(int displayId, in Surface surface);

    /** Removes the display; its content is destroyed (DESTROY_CONTENT_ON_REMOVAL). */
    void releaseDisplay(int displayId);

    /** Starts component (pkg/cls) on displayId; returns 0 on success. */
    int launch(String component, int displayId);

    oneway void injectKey(int displayId, int action, int keyCode, int repeat, int metaState, int source,
            long receivedAtNanos);

    oneway void injectMotion(int displayId, int action, int buttonState, in int[] pointerIds,
            in int[] toolTypes, in float[] xs, in float[] ys, int source, long receivedAtNanos);

    oneway void injectScroll(int displayId, float x, float y, float vScroll, float hScroll,
            long receivedAtNanos);

    /** [injected, dropped, p95 latency µs, max latency µs] since the last call. */
    long[] stats();

    /** The app's listener; its death ends the helper. */
    void setListener(IRiriHelperListener listener);

    oneway void exit();
}
