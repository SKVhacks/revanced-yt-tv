package com.myyt.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Map;

/**
 * ReVanced YT TV: opens YouTube's TV site full screen, with no browser bar, as if it were a native TV app. The remote's
 * keys go to the page (arrows, OK as Enter, media keys); Back goes back in the page, and leaves the app from the
 * first page. Ads and trackers are blocked by Brave's adblock-rust engine (AdBlock).
 */
public final class MainActivity extends Activity {
    static final String HOME_URL = "https://www.youtube.com/tv";
    /**
     * Switches in the address that YouTube's TV app reads before the server's settings for this identity (tested
     * 2026-11-25). The server sends "force_voice_incapable", which hides the mic button on the search screen: the first
     * two bring it back, recording through the Web Speech API (Voice.POLYFILL). It also sends
     * "force_memory_saving_mode", which drops the gradients behind cards and the player controls and uses small
     * thumbnails: env_isLimitedAnimation keeps the full look with animations still off, for weak boxes.
     */
    static final String START_URL = HOME_URL + "?env_enableSoftMic=true&env_enableWebSpeech=true&env_isLimitedAnimation=true";

    /** Runs in YouTube's pages before their own code: decoder/HDR answers and the look (MediaCaps), then voice. */
    static final String PAGE_SCRIPT = MediaCaps.SCRIPT + "\n" + Voice.POLYFILL;
    /**
     * youtube.com/tv only opens for TV browsers; others are sent to the desktop site. A Samsung (Tizen) identity gets
     * clear (non-DRM) streams that start at once with the ad scriptlets on. The official Android TV (Cobalt) identity
     * would show YouTube's own mic button, but it is served Widevine streams and, with ads stripped, waits ~25 s before
     * every video (tested 2026-11-05), so it is not used. Voice search comes from the remote's mic key (Voice).
     */
    static final String TV_USER_AGENT = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Version/6.0 TV Safari/537.36";

    private WebView web;
    private Voice voice;
    private FrameLayout root;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    /** The page that is open, for third-party checks; written on the main thread, read on WebView's threads. */
    private volatile String pageUrl = HOME_URL;
    private long lastBack;
    private final Handler main = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);   // lets window.close() reach onCloseWindow
        s.setUserAgentString(TV_USER_AGENT);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        // Voice search: the page's mic button and the remote's mic key (see Voice).
        voice = new Voice(this, web);
        web.addJavascriptInterface(voice, Voice.BRIDGE);
        web.addJavascriptInterface(new MediaCaps(this), MediaCaps.BRIDGE);
        installVoiceScript();

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();

        // Ask for the microphone on the first start, and open YouTube only once it is answered. YouTube decides whether
        // to show its mic button while the page starts: if the page loads while the permission dialog is still open,
        // its own microphone check is refused and the button stays hidden.
        savedState = state;
        waitingForFirstMic = state == null && !hasMic();
        if (waitingForFirstMic) askForMic();

        // Load the engine first (about a second on this box), then the site, so the first page is filtered too.
        new Thread(() -> {
            boolean ok = AdBlock.load(getApplicationContext());
            String ytScript = ok ? AdBlock.script(HOME_URL) : "";
            main.post(() -> {
                if (!ok) Toast.makeText(this, "Ad blocker could not start; opening without it", Toast.LENGTH_LONG).show();
                installYouTubeScript(ytScript);
                engineReady = true;
                openSiteIfReady();
            });
        }, "adblock-load").start();
    }

    private static final int REQ_MIC_DIALOG = 44;
    private Bundle savedState;
    private boolean engineReady, siteOpened, waitingForFirstMic;
    /** True while Android's microphone dialog is on screen; a second request then would be cancelled at once. */
    private boolean micDialogOpen;

    boolean hasMic() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Shows Android's microphone dialog, unless it is already showing; every waiter is answered in onRequestPermissionsResult. */
    void askForMic() {
        if (micDialogOpen) return;
        micDialogOpen = true;
        requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC_DIALOG);
    }

    /** Opens YouTube once the ad blocker is loaded and the first-start microphone question is answered. */
    private void openSiteIfReady() {
        if (siteOpened || !engineReady || waitingForFirstMic) return;
        siteOpened = true;
        String query = searchQuery(getIntent());
        if (savedState == null) web.loadUrl(query == null ? START_URL : START_URL + "#/search?q=" + android.net.Uri.encode(query));
        else web.restoreState(savedState);
        savedState = null;
    }


    /** The ReVanced YT TV activity while it is on screen, for MicKeyService; null otherwise. */
    static volatile MainActivity resumed;

    /** The remote's mic button, passed on by MicKeyService (main thread). */
    void onMicKey() {
        if (fullscreenView != null) exitFullscreen();
        voice.listenForKey();
    }

    /** The text of a search handed to the app, or null. */
    private static String searchQuery(android.content.Intent intent) {
        if (intent == null || !android.content.Intent.ACTION_SEARCH.equals(intent.getAction())) return null;
        String q = intent.getStringExtra(android.app.SearchManager.QUERY);
        return q == null || q.trim().isEmpty() ? null : q.trim();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String q = searchQuery(intent);
        if (q != null) Voice.openSearch(web, q);
    }

    /** True once the scriptlets run at document start; otherwise they are injected as each page starts. */
    private boolean documentStartScript;

    /**
     * Registers YouTube's scriptlets (uBlock Origin's, as Brave uses them) to run in every youtube.com page and frame
     * before YouTube's own code, which is when they can strip the ad placements out of the player data.
     */
    private void installYouTubeScript(String script) {
        if (script.isEmpty()) return;
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, script,
                        java.util.Collections.singleton("https://www.youtube.com"));
                documentStartScript = true;
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** True once the voice polyfill runs at document start; otherwise it is injected as each page loads. */
    private boolean voiceStartScript;

    private void installVoiceScript() {
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(web, PAGE_SCRIPT,
                        java.util.Collections.singleton("https://www.youtube.com"));
                voiceStartScript = true;
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** The page's microphone request, waiting for the Android permission. */
    private android.webkit.PermissionRequest pendingMicRequest;
    private java.util.List<String> pendingGrant;

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_MIC_DIALOG) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }
        micDialogOpen = false;
        // The real state, not grantResults: those are empty when Android cancels a request.
        boolean granted = hasMic();

        // The page's own microphone request (getUserMedia).
        android.webkit.PermissionRequest r = pendingMicRequest;
        pendingMicRequest = null;
        java.util.List<String> grant = pendingGrant == null ? new java.util.ArrayList<>() : pendingGrant;
        pendingGrant = null;
        if (r != null) {
            if (granted) grant.add(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE);
            if (grant.isEmpty()) r.deny();
            else r.grant(grant.toArray(new String[0]));
        }

        // A voice search waiting for the microphone.
        voice.onMicAnswer(granted);

        if (waitingForFirstMic) {
            waitingForFirstMic = false;
            if (!granted) {
                Toast.makeText(this, "Voice search needs the microphone. It will ask again when you press the mic.",
                        Toast.LENGTH_LONG).show();
            }
            openSiteIfReady();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (!voice.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = this;
        web.onResume();
        web.resumeTimers();
    }

    @Override
    protected void onPause() {
        if (resumed == this) resumed = null;
        // Stop video and scripts while another app is in front.
        if (!isVoiceScreenOpening()) voice.cancel();
        web.onPause();
        web.pauseTimers();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        voice.destroy();
        root.removeView(web);
        web.destroy();
        super.onDestroy();
    }

    // ---- keys -------------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) onBackKey();
            return true;
        }
        // The remote's mic / search key starts a voice search.
        if (code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_ASSIST) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) voice.listenForKey();
            return true;
        }
        // OK on the remote acts as Enter in the page.
        if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
            KeyEvent enter = new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                    KeyEvent.KEYCODE_ENTER, event.getRepeatCount(), event.getMetaState());
            return web.dispatchKeyEvent(enter);
        }
        // Channel up/down page through the list like Page Up / Page Down.
        if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            int mapped = code == KeyEvent.KEYCODE_CHANNEL_UP ? KeyEvent.KEYCODE_PAGE_UP : KeyEvent.KEYCODE_PAGE_DOWN;
            return web.dispatchKeyEvent(new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
                    mapped, event.getRepeatCount(), event.getMetaState()));
        }
        if (fullscreenView == null && !web.hasFocus()) web.requestFocus();
        return super.dispatchKeyEvent(event);
    }

    /** The system voice screen (fallback recognizer) pauses this activity; that must not cancel the search. */
    private boolean voiceScreenOpening;

    private boolean isVoiceScreenOpening() {
        boolean v = voiceScreenOpening;
        voiceScreenOpening = false;
        return v;
    }

    @Override
    public void startActivityForResult(android.content.Intent intent, int requestCode, Bundle options) {
        if (requestCode == Voice.REQ_SPEECH) voiceScreenOpening = true;
        super.startActivityForResult(intent, requestCode, options);
    }

    private void onBackKey() {
        if (fullscreenView != null) {
            exitFullscreen();
        } else if (isYouTubeTv()) {
            // YouTube's TV app handles Back itself (Escape, as on a smart TV): it closes the player, goes back a
            // screen, and on its home screen asks "Exit YouTube?", which closes the window (onCloseWindow).
            long now = System.currentTimeMillis();
            web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0));
            web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0));
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            long now = System.currentTimeMillis();
            if (now - lastBack < 2000) {
                finish();
            } else {
                lastBack = now;
                Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean isYouTubeTv() {
        String url = pageUrl;
        return url != null && url.contains("youtube.com/tv");
    }

    // ---- page loading and ad blocking ---------------------------------------------------------------------

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request.isForMainFrame()) return null;   // never block the site itself
            String url = request.getUrl().toString();
            if (!url.startsWith("http")) return null;
            boolean blocked = AdBlock.shouldBlock(url, pageUrl, requestType(request));
            if (DEBUG_LOG) android.util.Log.i("BraveTVnet", (blocked ? "BLOCK " : "ok ") + request.getMethod() + " "
                    + (url.length() > 160 ? url.substring(0, 160) : url));
            if (blocked) {
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // Stay inside the app for web pages; other schemes (intent:, market:) are ignored.
            String scheme = request.getUrl().getScheme();
            return scheme == null || !(scheme.equals("http") || scheme.equals("https"));
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            pageUrl = url;
            // Older WebViews: as early as an app can run a script without document-start support.
            if (!documentStartScript) {
                String script = AdBlock.script(url);
                if (!script.isEmpty()) view.evaluateJavascript(script, null);
            }
            if (!voiceStartScript && url != null && url.startsWith("https://www.youtube.com")) {
                view.evaluateJavascript(PAGE_SCRIPT, null);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageUrl = url;
            injectHideCss(url);
            if (!voiceStartScript && url != null && url.startsWith("https://www.youtube.com")) {
                view.evaluateJavascript(PAGE_SCRIPT, null);
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            // Single-page apps change the address without a new page load.
            pageUrl = url;
            injectHideCss(url);
        }
    }

    /** Adds the engine's element-hiding rules for this page as a stylesheet. */
    private void injectHideCss(String url) {
        String css = AdBlock.hideCss(url);
        if (css.isEmpty()) return;
        String quoted = org.json.JSONObject.quote(css);
        web.evaluateJavascript("(function(){var id='myyt-adblock-css';var s=document.getElementById(id);"
                + "if(!s){s=document.createElement('style');s.id=id;(document.head||document.documentElement).appendChild(s);}"
                + "s.textContent=" + quoted + ";})();", null);
    }

    /** The adblock request type, from the Accept header and the file extension. */
    private static String requestType(WebResourceRequest request) {
        Map<String, String> headers = request.getRequestHeaders();
        String accept = "";
        if (headers != null) {
            for (Map.Entry<String, String> h : headers.entrySet()) {
                if ("accept".equalsIgnoreCase(h.getKey())) accept = h.getValue().toLowerCase(Locale.ROOT);
            }
        }
        String path = request.getUrl().getPath();
        path = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (accept.startsWith("text/html")) return "subdocument";
        if (accept.startsWith("text/css") || path.endsWith(".css")) return "stylesheet";
        if (accept.startsWith("image/") || path.matches(".*\\.(png|jpe?g|gif|webp|svg|ico|avif)$")) return "image";
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "script";
        if (path.matches(".*\\.(mp4|webm|m3u8|ts|m4s|mp3|aac)$")) return "media";
        if (accept.contains("application/json")) return "xmlhttprequest";
        return "other";
    }

    // ---- full-screen video (the player's own full-screen button) --------------------------------------------

    /** Temporary diagnostics: every request and console message to logcat. */
    static final boolean DEBUG_LOG = false;

    private final class Chrome extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
            if (DEBUG_LOG) android.util.Log.i("BraveTVcon", m.messageLevel() + " " + m.message());
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullscreenView != null) {
                callback.onCustomViewHidden();
                return;
            }
            fullscreenView = view;
            fullscreenCallback = callback;
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.setVisibility(View.GONE);
            view.requestFocus();
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }

        /**
         * YouTube only: the microphone (its mic button records with getUserMedia) and protected media (the TV client
         * is served Widevine-encrypted streams; without it every video says "This video format is not supported").
         */
        @Override
        public void onPermissionRequest(android.webkit.PermissionRequest request) {
            android.net.Uri origin = request.getOrigin();
            String host = origin == null ? null : origin.getHost();
            boolean youtube = host != null && (host.equals("youtube.com") || host.endsWith(".youtube.com"));
            if (!youtube) {
                request.deny();
                return;
            }
            boolean wantsMic = false;
            java.util.List<String> grant = new java.util.ArrayList<>();
            for (String r : request.getResources()) {
                if (android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) grant.add(r);
                if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
            }
            if (wantsMic && !hasMic()) {
                if (pendingMicRequest != null) pendingMicRequest.deny();   // superseded
                pendingMicRequest = request;
                pendingGrant = grant;
                askForMic();
                return;
            }
            if (wantsMic) grant.add(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE);
            if (grant.isEmpty()) request.deny();
            else request.grant(grant.toArray(new String[0]));
        }

        @Override
        public void onCloseWindow(WebView window) {
            finish();   // YouTube's "Exit YouTube?" -> OK
        }
    }

    private void exitFullscreen() {
        if (fullscreenView == null) return;
        root.removeView(fullscreenView);
        fullscreenView = null;
        web.setVisibility(View.VISIBLE);
        web.requestFocus();
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
    }
}
