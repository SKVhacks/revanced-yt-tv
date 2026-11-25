package com.myyt.tv;

import android.app.Activity;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.view.Display;
import android.webkit.JavascriptInterface;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the device can really play, for YouTube's TV player.
 *
 * <p>The TV player asks {@code MediaSource.isTypeSupported} with extra parameters, as TV browsers (Cobalt) answer them:
 * {@code width}, {@code height}, {@code framerate} and {@code eotf} (HDR). Chrome's WebView ignores those parameters and
 * says yes to everything, so the player cannot trust it: it caps the quality to the size of the page (720p on a phone)
 * and never picks HDR. {@link #SCRIPT} answers those parameters from Android's hardware decoders and the display's
 * HDR support, so the player offers what the device can decode (up to 4K) and HDR only on an HDR screen.
 *
 * <p>The script also removes the black boxes YouTube draws behind titles and video details on low-end TVs
 * ({@code .app-quality-root ... {background-color:#000;display:inline}}), with a text shadow instead.
 */
final class MediaCaps {
    static final String BRIDGE = "MyYtMedia";

    private final Activity activity;
    private final Map<String, Boolean> cache = new ConcurrentHashMap<>();
    private volatile List<MediaCodecInfo> decoders;

    MediaCaps(Activity activity) {
        this.activity = activity;
    }

    /** True if the screen shows this HDR transfer: "smpte2084" (HDR10, PQ) or "arib-std-b67" (HLG). */
    @JavascriptInterface
    public boolean hdr(String eotf) {
        Boolean known = cache.get("hdr:" + eotf);
        if (known != null) return known;
        boolean ok = false;
        try {
            Display d = activity.getWindowManager().getDefaultDisplay();
            Display.HdrCapabilities caps = d == null ? null : d.getHdrCapabilities();
            if (caps != null) {
                for (int t : caps.getSupportedHdrTypes()) {
                    if ("smpte2084".equals(eotf) && (t == Display.HdrCapabilities.HDR_TYPE_HDR10
                            || t == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS)) ok = true;
                    if ("arib-std-b67".equals(eotf) && t == Display.HdrCapabilities.HDR_TYPE_HLG) ok = true;
                }
            }
        } catch (RuntimeException ignored) {
        }
        cache.put("hdr:" + eotf, ok);
        return ok;
    }

    /**
     * True if a decoder plays this codec ("vp09.02.51.10...", "av01.0.12M.10...", "avc1...") at this size and frame
     * rate (0 = not asked). Hardware decoders only, when the device has one for the codec; a codec Android does not
     * know is left to Chrome (true).
     */
    @JavascriptInterface
    public boolean video(String codecs, int width, int height, double fps) {
        String key = codecs + "|" + width + "|" + height + "|" + fps;
        Boolean known = cache.get(key);
        if (known != null) return known;
        boolean ok;
        try {
            ok = check(codecs == null ? "" : codecs.toLowerCase(Locale.ROOT).trim(), width, height, fps);
        } catch (RuntimeException e) {
            ok = true;
        }
        cache.put(key, ok);
        return ok;
    }

    private boolean check(String codec, int width, int height, double fps) {
        String mime;
        int[] profiles = null;   // one of these must be listed (10-bit / HDR streams)
        if (codec.startsWith("vp09") || codec.startsWith("vp9")) {
            mime = "video/x-vnd.on2.vp9";
            if (codec.startsWith("vp09.02") || codec.startsWith("vp09.03")) {
                profiles = new int[]{MediaCodecInfo.CodecProfileLevel.VP9Profile2,
                        MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR,
                        MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus};
            }
        } else if (codec.startsWith("av01")) {
            mime = "video/av01";
            String[] p = codec.split("\\.");
            if (p.length > 3 && "10".equals(p[3])) {
                profiles = new int[]{MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
                        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
                        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus};
            }
        } else if (codec.startsWith("avc1") || codec.startsWith("avc3")) {
            mime = "video/avc";
        } else if (codec.startsWith("hev1") || codec.startsWith("hvc1")) {
            mime = "video/hevc";
        } else {
            return true;
        }

        List<MediaCodecInfo> hardware = new ArrayList<>(), software = new ArrayList<>();
        for (MediaCodecInfo info : decoders()) {
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) {
                    (isHardware(info) ? hardware : software).add(info);
                    break;
                }
            }
        }
        boolean soft = hardware.isEmpty();
        // Software decoding is too slow above 1080p30 on a phone or a TV box.
        if (soft && (height > 1080 || width > 1920 || fps > 30)) return false;
        for (MediaCodecInfo info : soft ? software : hardware) {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
            if (profiles != null && !hasProfile(caps, profiles)) continue;
            MediaCodecInfo.VideoCapabilities v = caps.getVideoCapabilities();
            if (v == null) continue;
            if (width > 0 && height > 0) {
                if (fps > 0 ? v.areSizeAndRateSupported(width, height, fps) : v.isSizeSupported(width, height)) return true;
                // Some decoders list sizes only in landscape or portrait.
                if (fps > 0 ? v.areSizeAndRateSupported(height, width, fps) : v.isSizeSupported(height, width)) return true;
            } else if (height > 0) {
                if (v.getSupportedHeights().contains(height) || v.getSupportedWidths().contains(height)) return true;
            } else if (width > 0) {
                if (v.getSupportedWidths().contains(width) || v.getSupportedHeights().contains(width)) return true;
            } else if (fps > 0) {
                if (v.getSupportedFrameRates().contains((int) Math.ceil(fps))) return true;
            } else {
                return true;
            }
        }
        return false;
    }

    private static boolean hasProfile(MediaCodecInfo.CodecCapabilities caps, int[] profiles) {
        for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
            for (int p : profiles) if (pl.profile == p) return true;
        }
        return false;
    }

    private static boolean isHardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated();
        String n = info.getName().toLowerCase(Locale.ROOT);
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.endsWith(".sw"));
    }

    private List<MediaCodecInfo> decoders() {
        List<MediaCodecInfo> list = decoders;
        if (list != null) return list;
        list = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) list.add(info);
        }
        decoders = list;
        return list;
    }

    /** Runs in YouTube's pages before their own code; safe to run twice. */
    static final String SCRIPT = """
(function(){
if(window.__myytMedia)return;window.__myytMedia=1;
var fixed={},sels=[],style=null;
function scan(){var added=false;for(var i=0;i<document.styleSheets.length;i++){var sh=document.styleSheets[i],rs;try{rs=sh.cssRules;}catch(e){continue;}
 if(!rs||sh.ownerNode===style)continue;for(var j=0;j<rs.length;j++){var r=rs[j],t=r.selectorText;
 if(t&&!fixed[t]&&t.indexOf('app-quality-root')>=0&&r.style.backgroundColor==='rgb(0, 0, 0)'&&r.style.display==='inline'){fixed[t]=1;sels.push(t);added=true;}}}
 if(!added)return;if(!style){style=document.createElement('style');style.id='myyt-look';}
 if(!style.parentNode)(document.head||document.documentElement).appendChild(style);
 style.textContent=sels.join(',')+'{background-color:transparent!important;text-shadow:0 0 .25rem rgba(0,0,0,.9),0 .0625rem .125rem rgba(0,0,0,.8)}';}
var pend=0;function soon(){if(pend)return;pend=setTimeout(function(){pend=0;scan();},50);}
function watch(){scan();try{new MutationObserver(soon).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',watch);else watch();
var M=window.MyYtMedia,MS=window.MediaSource;if(!M||!MS||!MS.isTypeSupported)return;
var orig=MS.isTypeSupported.bind(MS),cache={},hdrScreen=!!(M.hdr('smpte2084')||M.hdr('arib-std-b67')),
bad={width:99999,height:99999,framerate:9999,bitrate:2e9,channels:99,eotf:'catavision'};
function check(t){var parts=t.split(';'),keep=[parts[0]],p={},codecs='';
 for(var i=1;i<parts.length;i++){var s=parts[i],j=s.indexOf('=');var k=(j<0?s:s.slice(0,j)).trim().toLowerCase(),v=j<0?'':s.slice(j+1).trim().replace(/^"|"$/g,'');
  if(k in bad){p[k]=v;}else{keep.push(s);if(k==='codecs')codecs=v;}}
 if(!orig(keep.join(';')))return false;
 for(var k in p){if(String(bad[k])===p[k]||(k!=='eotf'&&Number(p[k])>=Number(bad[k])))return false;}
 if(p.bitrate&&Number(p.bitrate)>1e9)return false;
 if(p.eotf&&(!hdrScreen||(p.eotf!=='bt709'&&!M.hdr(p.eotf))))return false;
 var video=/^video\\//i.test(parts[0]);
 if(video&&(p.width||p.height||p.framerate)&&!M.video(codecs||parts[0],Number(p.width)||0,Number(p.height)||0,Number(p.framerate)||0))return false;
 return true;}
MS.isTypeSupported=function(t){t=String(t);if(!(t in cache)){var r=false;try{r=check(t);}catch(e){r=orig(t);}cache[t]=r;}return cache[t];};
})();
            """;
}
