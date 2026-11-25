//! my yt: Brave's adblock-rust engine for the Android app, over JNI.
//!
//! The filter lists are compiled on the laptop (src/bin/compile.rs) into engine.dat, which the app ships as an
//! asset; the TV only deserializes it, which is quick even on a weak CPU. All calls come through
//! com.myyt.tv.AdBlock, whose methods are synchronized, so the engine is never used by two threads at once.

use adblock::request::Request;
use adblock::Engine;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

fn engine<'a>(handle: jlong) -> Option<&'a Engine> {
    if handle == 0 {
        None
    } else {
        // SAFETY: the handle is a Box<Engine> leaked by nativeLoad and never freed while the app runs.
        Some(unsafe { &*(handle as *const Engine) })
    }
}

fn string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

/// Loads a serialized engine; 0 when the data is not a valid engine file.
/// `resources` is the scriptlet library (uBlock Origin's scriptlets plus Brave's resources) as JSON.
#[no_mangle]
pub extern "system" fn Java_com_myyt_tv_AdBlock_nativeLoad(
    mut env: JNIEnv, _c: JClass, data: JByteArray, resources: JString,
) -> jlong {
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => return 0,
    };
    let mut engine = Engine::default();
    if engine.deserialize(&bytes).is_err() {
        return 0;
    }
    let json = string(&mut env, &resources);
    if let Ok(list) = serde_json::from_str::<Vec<adblock::resources::Resource>>(&json) {
        engine.use_resources(list);
    }
    Box::into_raw(Box::new(engine)) as jlong
}

/// The scriptlets for this page (YouTube's ad removal among them), to run before the page's own scripts.
#[no_mangle]
pub extern "system" fn Java_com_myyt_tv_AdBlock_nativeScript(
    mut env: JNIEnv, _c: JClass, handle: jlong, url: JString,
) -> jstring {
    let script = match engine(handle) {
        Some(engine) => engine.url_cosmetic_resources(&string(&mut env, &url)).injected_script,
        None => String::new(),
    };
    env.new_string(script).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// True when a network request for `url`, made by the page at `source`, should be blocked.
/// `kind` is an adblock request type: document, subdocument, script, image, stylesheet, xmlhttprequest, media, other.
#[no_mangle]
pub extern "system" fn Java_com_myyt_tv_AdBlock_nativeShouldBlock(
    mut env: JNIEnv, _c: JClass, handle: jlong, url: JString, source: JString, kind: JString,
) -> jboolean {
    let Some(engine) = engine(handle) else { return JNI_FALSE };
    let url = string(&mut env, &url);
    let source = string(&mut env, &source);
    let kind = string(&mut env, &kind);
    match Request::new(&url, &source, &kind, "GET") {
        Ok(req) if engine.check_network_request(&req).should_block() => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// CSS that hides this page's ad elements ("sel1,sel2{display:none!important}"), or "" when there are none.
#[no_mangle]
pub extern "system" fn Java_com_myyt_tv_AdBlock_nativeHideCss(
    mut env: JNIEnv, _c: JClass, handle: jlong, url: JString,
) -> jstring {
    let mut css = String::new();
    if let Some(engine) = engine(handle) {
        let url = string(&mut env, &url);
        let res = engine.url_cosmetic_resources(&url);
        let mut selectors: Vec<&String> = res.hide_selectors.iter().collect();
        selectors.sort();
        // Chunks keep one bad selector from voiding a whole giant rule.
        for chunk in selectors.chunks(200) {
            let joined: Vec<&str> = chunk.iter().map(|s| s.as_str()).collect();
            css.push_str(&joined.join(","));
            css.push_str("{display:none!important}\n");
        }
    }
    env.new_string(css).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
