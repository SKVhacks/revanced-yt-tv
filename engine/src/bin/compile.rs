//! Compiles filter lists into engine.dat for the app.
//! Usage: compile <out.dat> <resources.json> [trusted:]<list.txt>...
//! A list given as trusted:<path> may use trusted scriptlets (uBlock Origin and Brave lists, as in Brave).
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::{PermissionMask, Resource};
use adblock::Engine;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 3 {
        eprintln!("usage: compile <out.dat> <resources.json> [trusted:]<list.txt>...");
        std::process::exit(2);
    }
    let mut set = FilterSet::new(false);
    for arg in &args[2..] {
        let (trusted, path) = match arg.strip_prefix("trusted:") {
            Some(p) => (true, p),
            None => (false, arg.as_str()),
        };
        let text = std::fs::read_to_string(path).expect("read list");
        let opts = ParseOptions {
            permissions: if trusted { PermissionMask::from_bits(1) } else { PermissionMask::default() },
            ..ParseOptions::default()
        };
        set.add_filter_list(text, opts);
        eprintln!("{path}{}", if trusted { " (trusted)" } else { "" });
    }
    let engine = Engine::new_with_filter_set(set);
    let data = engine.serialize();
    std::fs::write(&args[0], &data).expect("write dat");

    // Load it back the way the app does, with the scriptlet resources, and check YouTube.
    let resources: Vec<Resource> =
        serde_json::from_str(&std::fs::read_to_string(&args[1]).expect("read resources")).expect("parse resources");
    let mut check = Engine::default();
    check.deserialize(&data).expect("deserialize");
    check.use_resources(resources);
    let probe = Request::new("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
        "https://example.com/", "script", "GET").unwrap();
    let yt = check.url_cosmetic_resources("https://www.youtube.com/tv");
    eprintln!("wrote {} bytes; adsbygoogle.js blocked: {}; youtube.com/tv script: {} bytes, removes adPlacements: {}",
        data.len(), check.check_network_request(&probe).should_block(), yt.injected_script.len(),
        yt.injected_script.contains("adPlacements"));
}
