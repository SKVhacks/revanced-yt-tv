package com.myyt.tv;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * The remote's mic button sends KEYCODE_ASSIST, which Android 9 hands to Google Assistant before any app sees it.
 * This accessibility service sees keys first: while ReVanced YT TV is on screen it takes the mic key and starts ReVanced YT TV's
 * own voice search. In every other app the key passes through untouched, so the mic still opens Google there.
 *
 * Enabled over ADB (appending to any services already listed):
 *   settings put secure enabled_accessibility_services com.myyt.tv/.MicKeyService
 *   settings put secure accessibility_enabled 1
 */
public final class MicKeyService extends AccessibilityService {
    static boolean isMicKey(int code) {
        return code == KeyEvent.KEYCODE_ASSIST || code == KeyEvent.KEYCODE_VOICE_ASSIST
                || code == KeyEvent.KEYCODE_SEARCH;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!isMicKey(event.getKeyCode())) return false;
        MainActivity front = MainActivity.resumed;
        if (front == null) return false;   // ReVanced YT TV is not on screen: Google Assistant as usual
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            front.runOnUiThread(front::onMicKey);
        }
        return true;   // the down, its repeats and the up, so Assistant never opens over the app
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
