package dev.ririprojector.helper;

interface IRiriHelperListener {
    /** No task is left on the display (e.g. Back finished the app). */
    oneway void onDisplayEmpty(int displayId);
}
