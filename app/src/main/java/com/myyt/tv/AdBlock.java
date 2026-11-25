package com.myyt.tv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Brave's adblock-rust engine (libmyyt_adblock.so), loaded from assets/engine.dat, which holds EasyList,
 * EasyPrivacy and Peter Lowe's list compiled on the laptop. Every method is synchronized: WebView asks from
 * several threads at once, and the engine is used by one at a time.
 */
final class AdBlock {
    private AdBlock() {}

    private static long handle;
    private static boolean libraryLoaded;

    static {
        try {
            System.loadLibrary("myyt_adblock");
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            libraryLoaded = false;
        }
    }

    private static native long nativeLoad(byte[] data, String resources);

    private static native String nativeScript(long handle, String url);

    private static native boolean nativeShouldBlock(long handle, String url, String source, String type);

    private static native String nativeHideCss(long handle, String url);

    /** Reads and loads the engine; call once, off the main thread. False when it could not be loaded. */
    static synchronized boolean load(Context context) {
        if (handle != 0) return true;
        if (!libraryLoaded) return false;
        try {
            byte[] engine = readAsset(context, "engine.dat");
            // uBlock Origin's scriptlets and Brave's resources: what removes YouTube's ads.
            String resources = new String(readAsset(context, "resources.json"), java.nio.charset.StandardCharsets.UTF_8);
            handle = nativeLoad(engine, resources);
        } catch (IOException e) {
            handle = 0;
        }
        return handle != 0;
    }

    private static byte[] readAsset(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1024 * 1024);
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0;) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** The scriptlets for a page, to run before its own scripts; "" when there are none. */
    static synchronized String script(String url) {
        if (handle == 0) return "";
        String s = nativeScript(handle, url);
        return s == null ? "" : s;
    }

    static synchronized boolean ready() {
        return handle != 0;
    }

    static synchronized boolean shouldBlock(String url, String source, String type) {
        return handle != 0 && nativeShouldBlock(handle, url, source == null ? "" : source, type);
    }

    static synchronized String hideCss(String url) {
        if (handle == 0) return "";
        String css = nativeHideCss(handle, url);
        return css == null ? "" : css;
    }
}
