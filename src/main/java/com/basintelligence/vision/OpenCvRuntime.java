package com.basintelligence.vision;

import nu.pattern.OpenCV;

/** Loads the OpenCV native library bundled in the openpnp jar exactly once per JVM. */
public final class OpenCvRuntime {

    private static volatile boolean loaded;

    private OpenCvRuntime() {
    }

    public static void ensureLoaded() {
        if (!loaded) {
            synchronized (OpenCvRuntime.class) {
                if (!loaded) {
                    OpenCV.loadLocally();
                    loaded = true;
                }
            }
        }
    }
}
