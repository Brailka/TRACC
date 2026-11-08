//! Нативный сетевой слой RVC.
//!
//! Запросы и ответы передаются JSON-строками: вызовов мало, а такой интерфейс не
//! требует кодогенерации и почти ничего не добавляет к размеру приложения.
//! Android обращается через JNI (`app.rvc.net.RustNet`, модуль `jni_api`),
//! iOS — через C ABI (`include/rvcnet.h`, модуль `ffi`).

mod doh;
mod geo;
mod http;
mod ping;

#[cfg(not(target_os = "android"))]
pub mod ffi;
#[cfg(target_os = "android")]
mod jni_api;

use serde_json::json;
use std::sync::OnceLock;

pub(crate) fn runtime() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .max_blocking_threads(4)
            .thread_name("rvcnet")
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

fn error_json(message: impl ToString) -> String {
    json!({ "ok": false, "error": message.to_string() }).to_string()
}

pub fn fetch_json(request: &str) -> String {
    match serde_json::from_str::<http::FetchRequest>(request) {
        Ok(req) => serde_json::to_string(&http::fetch(req)).unwrap_or_else(error_json),
        Err(e) => error_json(e),
    }
}

pub fn tcp_ping_json(request: &str) -> String {
    match serde_json::from_str::<ping::BatchRequest>(request) {
        Ok(req) => serde_json::to_string(&ping::tcp_batch(req)).unwrap_or_else(error_json),
        Err(e) => error_json(e),
    }
}

pub fn doh_json(request: &str) -> String {
    match serde_json::from_str::<doh::DohRequest>(request) {
        Ok(req) => match doh::resolve(req) {
            Ok(ips) => json!({ "ok": true, "ips": ips }).to_string(),
            Err(e) => error_json(e),
        },
        Err(e) => error_json(e),
    }
}

pub fn delay_json(request: &str) -> i64 {
    serde_json::from_str::<http::DelayRequest>(request).map(http::delay).unwrap_or(-1)
}

pub fn icmp_ping(host: &str, timeout_ms: i64) -> i64 {
    ping::icmp(host, timeout_ms.max(0) as u64)
}

pub fn trim_geo(src: &str, dst: &str, tags: &str) -> i64 {
    let tags: Vec<String> = tags.split(',').map(|s| s.to_string()).collect();
    geo::trim(src, dst, &tags).map(|n| n as i64).unwrap_or(-1)
}

pub fn geo_tags(src: &str) -> String {
    geo::tags(src).map(|t| t.join(",")).unwrap_or_default()
}

pub fn version() -> String {
    format!("rvcnet {}", env!("CARGO_PKG_VERSION"))
}
