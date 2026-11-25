package com.myyt.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Voice search. Android's WebView has no Web Speech API, so YouTube's TV page hides its microphone button. This
 * class supplies one: {@link #POLYFILL} defines {@code SpeechRecognition}/{@code webkitSpeechRecognition} in the page,
 * backed by Android's speech recognizer through the {@code MyYtVoice} bridge. The remote's mic/search key also starts
 * a search directly ({@link #listenForKey()}), whether or not the page shows its button.
 */
final class Voice {
    static final String BRIDGE = "MyYtVoice";
    static final int REQ_SPEECH = 42;
    /** Session id used for searches started from the remote's key rather than by the page. */
    private static final int KEY_SESSION = -1;

    private final MainActivity activity;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    /** The session that is listening (0 = none). */
    private int session;
    private boolean interim;
    /** A session waiting for the microphone permission. */
    private int pendingId;
    private String pendingLang;
    private boolean pendingInterim;

    Voice(MainActivity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    // ---- called by the page (JavaBridge thread) ----------------------------------------------------------

    @JavascriptInterface
    public void start(int id, String lang, boolean interimResults) {
        main.post(() -> begin(id, lang, interimResults));
    }

    /** For the page's permission check: is the microphone already granted? */
    @JavascriptInterface
    public boolean hasMic() {
        return activity.hasMic();
    }

    @JavascriptInterface
    public void stop(int id) {
        main.post(() -> {
            if (session == id && recognizer != null) recognizer.stopListening();
        });
    }

    @JavascriptInterface
    public void abort(int id) {
        main.post(() -> {
            if (session == id) finish("aborted");
        });
    }

    // ---- called by the activity (main thread) --------------------------------------------------------------

    /** The remote's mic/search key: listen, then open YouTube's search results for what was said. */
    void listenForKey() {
        if (session == KEY_SESSION) {   // a second press cancels
            finish("aborted");
            return;
        }
        begin(KEY_SESSION, "", false);
    }

    /** Stops listening when the app leaves the screen. */
    void cancel() {
        if (session != 0) finish("aborted");
    }

    void destroy() {
        cancel();
        if (recognizer != null) recognizer.destroy();
        recognizer = null;
    }

    /** The answer to the microphone dialog; returns true if a voice search was waiting for it. */
    boolean onMicAnswer(boolean granted) {
        int id = pendingId;
        pendingId = 0;
        if (id == 0) return false;
        if (granted) {
            begin(id, pendingLang, pendingInterim);
        } else {
            send(id, "error", "not-allowed", false);
            send(id, "end", null, false);
            Toast.makeText(activity, "Voice search needs the microphone permission", Toast.LENGTH_LONG).show();
        }
        return true;
    }

    /** Result of the system voice screen, used when no background recognizer is installed. */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_SPEECH) return false;
        int id = session;
        if (id == 0) return true;
        ArrayList<String> texts = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (resultCode == Activity.RESULT_OK && texts != null && !texts.isEmpty()) {
            deliver(id, texts.get(0), true);
            session = 0;
            send(id, "end", null, false);
        } else {
            finish("no-speech");
        }
        return true;
    }

    // ---- recognition ---------------------------------------------------------------------------------------

    private void begin(int id, String lang, boolean interimResults) {
        if (session != 0 && session != id) finish("aborted");   // one session at a time
        if (!activity.hasMic()) {
            pendingId = id;
            pendingLang = lang;
            pendingInterim = interimResults;
            activity.askForMic();
            return;
        }
        session = id;
        interim = interimResults;

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
        if (lang != null && !lang.isEmpty()) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);

        if (SpeechRecognizer.isRecognitionAvailable(activity)) {
            try {
                if (recognizer == null) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
                    recognizer.setRecognitionListener(new Listener());
                }
                recognizer.startListening(intent);
                if (id == KEY_SESSION) Toast.makeText(activity, "Listening…", Toast.LENGTH_SHORT).show();
                return;
            } catch (RuntimeException e) {
                if (recognizer != null) recognizer.destroy();
                recognizer = null;   // fall through to the system voice screen
            }
        }
        try {
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Search YouTube");
            activity.startActivityForResult(intent, REQ_SPEECH);
            send(id, "start", null, false);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, "No voice recognizer on this box", Toast.LENGTH_LONG).show();
            finish("service-not-allowed");
        }
    }

    private final class Listener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) {
            send(session, "start", null, false);
            send(session, "audiostart", null, false);
        }
        @Override public void onBeginningOfSpeech() {
            send(session, "soundstart", null, false);
            send(session, "speechstart", null, false);
        }
        @Override public void onEndOfSpeech() {
            send(session, "speechend", null, false);
            send(session, "soundend", null, false);
            send(session, "audioend", null, false);
        }
        @Override public void onPartialResults(Bundle partial) {
            String text = first(partial);
            if (interim && text != null && !text.isEmpty()) deliver(session, text, false);
        }
        @Override public void onResults(Bundle results) {
            int id = session;
            String text = first(results);
            if (text == null || text.isEmpty()) {
                finish("no-speech");
                return;
            }
            deliver(id, text, true);
            session = 0;
            send(id, "end", null, false);
        }
        @Override public void onError(int error) {
            finish(errorName(error));
        }
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEvent(int eventType, Bundle params) {}
    }

    private static String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private static String errorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "no-speech";
            case SpeechRecognizer.ERROR_AUDIO: return "audio-capture";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER: return "network";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "not-allowed";
            default: return "aborted";
        }
    }

    /** Ends the current session with an error. */
    private void finish(String error) {
        int id = session;
        session = 0;
        if (recognizer != null) recognizer.cancel();
        if (id == 0) return;
        if (id == KEY_SESSION && !"aborted".equals(error)) {
            Toast.makeText(activity, "Didn't catch that", Toast.LENGTH_SHORT).show();
        }
        send(id, "error", error, false);
        send(id, "end", null, false);
    }

    private void deliver(int id, String text, boolean isFinal) {
        if (id == KEY_SESSION) {
            if (isFinal) openSearch(web, text);
            return;
        }
        send(id, "result", text, isFinal);
    }

    /** YouTube's TV app routes by the address fragment; this opens its search results for the text. */
    static void openSearch(WebView web, String text) {
        web.evaluateJavascript("(function(q){if(location.href.indexOf('youtube.com/tv')<0){"
                + "location.href='" + MainActivity.START_URL + "#/search?q='+encodeURIComponent(q);}"
                + "else{location.hash='#/search?q='+encodeURIComponent(q);}})(" + JSONObject.quote(text) + ");", null);
    }

    private void send(int id, String type, String data, boolean flag) {
        if (id == 0 || id == KEY_SESSION) return;
        String arg = data == null ? "null" : JSONObject.quote(data);
        web.evaluateJavascript("window.__myytVoice&&window.__myytVoice(" + id + ",'" + type + "'," + arg + ","
                + flag + ");", null);
    }

    /**
     * Runs in YouTube's pages before their own code: always lists a microphone (YouTube shows its mic button only when
     * enumerateDevices has an "audioinput"), answers the microphone permission check with "granted" or "prompt" (never
     * "denied", which hides the button; pressing it asks Android), then defines the Web Speech API on top of the bridge. Safe to run twice.
     */
    static final String POLYFILL = "(function(){"
            + "var md=navigator.mediaDevices;"
            + "if(md&&md.enumerateDevices&&!md.__myytMic){md.__myytMic=1;var en=md.enumerateDevices.bind(md);"
            + "md.enumerateDevices=function(){return en().catch(function(){return [];}).then(function(l){"
            + "l=Array.prototype.slice.call(l||[]);if(!l.some(function(d){return d.kind==='audioinput';}))"
            + "l.push({deviceId:'default',groupId:'default',kind:'audioinput',label:'Remote microphone',"
            + "toJSON:function(){return {deviceId:'default',groupId:'default',kind:'audioinput',label:'Remote microphone'};}});"
            + "return l;});};}"
            + "})();(function(){"
            + "var P=navigator.permissions;if(!P||!P.query||P.__myytMic)return;P.__myytMic=1;var q=P.query.bind(P);"
            + "P.query=function(d){var n=d&&d.name;if(n!=='microphone'&&n!=='audio_capture'&&n!=='speech')return q(d);"
            + "var B=window." + BRIDGE + ",s=(B&&B.hasMic())?'granted':'prompt';"
            + "var o=typeof EventTarget==='function'?new EventTarget():{addEventListener:function(){},removeEventListener:function(){}};"
            + "o.name=n;o.state=s;o.status=s;o.onchange=null;return Promise.resolve(o);};"
            + "})();(function(){"
            + "if(window.__myytVoice||!window." + BRIDGE + ")return;"
            + "var B=window." + BRIDGE + ",live={},next=1;"
            + "function R(){this.lang='';this.continuous=false;this.interimResults=false;this.maxAlternatives=1;"
            + "this.grammars=null;this._l={};this._id=0;}"
            + "R.prototype.addEventListener=function(t,f){(this._l[t]=this._l[t]||[]).push(f);};"
            + "R.prototype.removeEventListener=function(t,f){var a=this._l[t];if(a){var i=a.indexOf(f);if(i>=0)a.splice(i,1);}};"
            + "R.prototype.dispatchEvent=function(e){var self=this;e.target=e.currentTarget=e.srcElement=self;"
            + "var h=self['on'+e.type];if(typeof h==='function'){try{h.call(self,e);}catch(x){console.error(x);}}"
            + "(self._l[e.type]||[]).slice().forEach(function(f){try{f.call(self,e);}catch(x){console.error(x);}});return true;};"
            + "R.prototype.start=function(){if(this._id)throw new DOMException('recognition has already started','InvalidStateError');"
            + "this._id=next++;live[this._id]=this;B.start(this._id,this.lang||navigator.language||'',!!this.interimResults);};"
            + "R.prototype.stop=function(){if(this._id)B.stop(this._id);};"
            + "R.prototype.abort=function(){if(this._id)B.abort(this._id);};"
            + "function list(a){a.item=function(i){return this[i];};return a;}"
            + "window.__myytVoice=function(id,type,data,flag){var r=live[id];if(!r)return;"
            + "if(type==='result'){var res=list([{transcript:data,confidence:0.9}]);res.isFinal=flag;"
            + "r.dispatchEvent({type:'result',resultIndex:0,results:list([res])});}"
            + "else if(type==='error'){r.dispatchEvent({type:'error',error:data,message:data});}"
            + "else if(type==='end'){delete live[id];r._id=0;r.dispatchEvent({type:'end'});}"
            + "else{r.dispatchEvent({type:type});}};"
            + "window.SpeechRecognition=window.webkitSpeechRecognition=R;"
            + "})();";
}
