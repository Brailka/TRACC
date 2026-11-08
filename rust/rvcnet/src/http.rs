//! HTTP-клиент для подписок, профилей маршрутизации и геофайлов.
//!
//! Перенаправления обрабатываются вручную: приватные заголовки (HWID и т.п.)
//! уходят только на исходный хост, а переход с https на http запрещён.

use crate::runtime;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::Write;
use std::net::{IpAddr, SocketAddr};
use std::sync::OnceLock;
use std::time::Duration;

const MAX_REDIRECTS: usize = 5;

fn sensitive_header(name: &str) -> bool {
    matches!(name.to_ascii_lowercase().as_str(),
        "authorization" | "proxy-authorization" | "cookie" | "host" | "x-api-key")
}

#[cfg(test)]
mod redirect_header_tests {
    use super::sensitive_header;
    #[test]
    fn credentials_cannot_follow_cross_origin_redirects() {
        for name in ["Authorization", "COOKIE", "Proxy-Authorization", "Host", "X-Api-Key"] {
            assert!(sensitive_header(name));
        }
        assert!(!sensitive_header("User-Agent"));
        assert!(!sensitive_header("Accept"));
    }

    #[test]
    fn redirect_does_not_send_credentials_to_another_port() {
        use std::io::{Read, Write};
        use std::net::TcpListener;
        let first = TcpListener::bind("127.0.0.1:0").unwrap();
        let second = TcpListener::bind("127.0.0.1:0").unwrap();
        let origin = format!("http://{}/", first.local_addr().unwrap());
        let destination = format!("http://{}/", second.local_addr().unwrap());
        let a = std::thread::spawn(move || {
            let (mut stream, _) = first.accept().unwrap();
            stream.set_read_timeout(Some(std::time::Duration::from_secs(5))).unwrap();
            let mut request = [0; 8192];
            let count = stream.read(&mut request).unwrap();
            let text = String::from_utf8_lossy(&request[..count]).to_lowercase();
            assert!(text.contains("authorization: bearer secret"));
            write!(stream, "HTTP/1.1 302 Found\r\nLocation: {}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", destination).unwrap();
        });
        let b = std::thread::spawn(move || {
            let (mut stream, _) = second.accept().unwrap();
            stream.set_read_timeout(Some(std::time::Duration::from_secs(5))).unwrap();
            let mut request = [0; 8192];
            let count = stream.read(&mut request).unwrap();
            let text = String::from_utf8_lossy(&request[..count]).to_lowercase();
            assert!(!text.contains("authorization:"));
            assert!(!text.contains("cookie:"));
            stream.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK").unwrap();
        });
        let req = serde_json::from_value(serde_json::json!({
            "url": origin, "headers": {"Authorization": "Bearer secret", "Cookie": "token=secret"},
            "timeoutMs": 5000
        })).unwrap();
        let result = super::fetch(req);
        assert!(result.ok, "{:?}", result.error);
        assert_eq!(result.body.as_deref(), Some("OK"));
        a.join().unwrap();
        b.join().unwrap();
    }
}

/// Открытый http допустим только внутри устройства и локальной сети (тестовые панели, роутер).
/// В интернет подписки, профили и геофайлы идут только по https: по http их можно подменить
/// по пути (подсунуть свой сервер и читать весь трафик), а HWID ушёл бы открытым текстом.
fn cleartext_allowed(url: &reqwest::Url) -> bool {
    if url.scheme() != "http" {
        return url.scheme() == "https";
    }
    match url.host() {
        Some(url::Host::Domain(d)) => {
            let d = d.trim_end_matches('.').to_ascii_lowercase();
            d == "localhost" || d.ends_with(".localhost") || d.ends_with(".local") || d.ends_with(".lan")
        }
        Some(url::Host::Ipv4(ip)) => {
            let o = ip.octets();
            ip.is_private() || ip.is_loopback() || ip.is_link_local() || (o[0] == 100 && (o[1] & 0xc0) == 64)
        }
        Some(url::Host::Ipv6(ip)) => {
            let first = ip.segments()[0];
            ip.is_loopback() || (first & 0xfe00) == 0xfc00 || (first & 0xffc0) == 0xfe80
        }
        None => false,
    }
}

fn default_timeout() -> u64 {
    15_000
}

fn default_max() -> u64 {
    8 * 1024 * 1024
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FetchRequest {
    pub url: String,
    #[serde(default)]
    pub headers: HashMap<String, String>,
    #[serde(default)]
    pub private_headers: HashMap<String, String>,
    #[serde(default = "default_timeout")]
    pub timeout_ms: u64,
    #[serde(default)]
    pub proxy: Option<String>,
    #[serde(default = "default_max")]
    pub max_bytes: u64,
    #[serde(default)]
    pub resolve: HashMap<String, String>,
    #[serde(default)]
    pub out_file: Option<String>,
    #[serde(default)]
    pub host_header: Option<String>,
    /// Только статус и заголовки: тело не читается (проверка доступности сайтов).
    #[serde(default)]
    pub discard_body: bool,
}

#[derive(Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct FetchResponse {
    pub ok: bool,
    pub status: u16,
    pub headers: HashMap<String, String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub body: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub final_url: Option<String>,
    pub bytes: u64,
    pub elapsed_ms: u64,
}

fn base_builder() -> reqwest::ClientBuilder {
    reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(8))
        .pool_idle_timeout(Duration::from_secs(20))
        .pool_max_idle_per_host(2)
        .tcp_nodelay(true)
        .use_rustls_tls()
}

/// Обычный клиент без прокси переиспользуется: корневые сертификаты и пул соединений создаются один раз.
fn shared_client() -> Result<&'static reqwest::Client, String> {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    if let Some(c) = CLIENT.get() {
        return Ok(c);
    }
    let client = base_builder().build().map_err(|e| e.to_string())?;
    Ok(CLIENT.get_or_init(|| client))
}

fn client_for(req: &FetchRequest) -> Result<reqwest::Client, String> {
    if req.proxy.is_none() && req.resolve.is_empty() && req.host_header.is_none() {
        return shared_client().cloned();
    }
    let mut builder = base_builder();
    if let Some(proxy) = &req.proxy {
        builder = builder.proxy(reqwest::Proxy::all(proxy).map_err(|e| e.to_string())?);
    }
    for (host, ip) in &req.resolve {
        if let Ok(ip) = ip.parse::<IpAddr>() {
            builder = builder.resolve(host, SocketAddr::new(ip, 0));
        }
    }
    if req.host_header.is_some() {
        // Фронтинг через подмену Host надёжно работает только в HTTP/1.1.
        builder = builder.http1_only();
    }
    builder.build().map_err(|e| e.to_string())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DelayRequest {
    pub url: String,
    #[serde(default)]
    pub proxy: Option<String>,
    #[serde(default = "default_method")]
    pub method: String,
    #[serde(default = "default_attempts")]
    pub attempts: u32,
    #[serde(default = "default_delay_timeout")]
    pub timeout_ms: u64,
}

fn default_method() -> String {
    "HEAD".into()
}

fn default_attempts() -> u32 {
    2
}

fn default_delay_timeout() -> u64 {
    8_000
}

/// Задержка HTTP-запроса (по умолчанию HEAD) через прокси: несколько попыток по одному соединению,
/// берётся лучшая — первая включает рукопожатие, следующие показывают чистую задержку канала.
pub fn delay(req: DelayRequest) -> i64 {
    let timeout = Duration::from_millis(req.timeout_ms.clamp(1_000, 30_000));
    runtime().block_on(async move {
        let mut builder = base_builder();
        if let Some(proxy) = &req.proxy {
            builder = builder.proxy(reqwest::Proxy::all(proxy).ok()?);
        }
        let client = builder.build().ok()?;
        let method = reqwest::Method::from_bytes(req.method.as_bytes()).unwrap_or(reqwest::Method::HEAD);
        let mut best: Option<u64> = None;
        for _ in 0..req.attempts.clamp(1, 5) {
            let started = std::time::Instant::now();
            match client.request(method.clone(), &req.url).timeout(timeout).send().await {
                Ok(resp) if resp.status().is_success() || resp.status().is_redirection() => {
                    let ms = started.elapsed().as_millis().max(1) as u64;
                    best = Some(best.map_or(ms, |b| b.min(ms)));
                }
                _ => {}
            }
        }
        best
    })
    .map_or(-1, |ms| ms as i64)
}

pub fn fetch(req: FetchRequest) -> FetchResponse {
    let started = std::time::Instant::now();
    let budget = Duration::from_millis(req.timeout_ms.clamp(1_000, if req.out_file.is_some() { 900_000 } else { 120_000 }));
    let mut response = runtime().block_on(async {
        tokio::time::timeout(budget, fetch_async(&req)).await
            .map_err(|_| "сервер не ответил вовремя".to_string())?
    }).unwrap_or_else(|error| FetchResponse {
        error: Some(error),
        ..Default::default()
    });
    response.elapsed_ms = started.elapsed().as_millis() as u64;
    response
}

async fn fetch_async(req: &FetchRequest) -> Result<FetchResponse, String> {
    let client = client_for(req)?;
    let origin = reqwest::Url::parse(&req.url).map_err(|e| format!("неверный адрес: {e}"))?;
    let mut current = origin.clone();
    let timeout = Duration::from_millis(req.timeout_ms.clamp(1_000, if req.out_file.is_some() { 900_000 } else { 120_000 }));

    for _ in 0..=MAX_REDIRECTS {
        if !cleartext_allowed(&current) {
            return Err("незащищённый адрес (http): нужна ссылка https".into());
        }
        let same_origin = current.host_str() == origin.host_str()
            && current.scheme() == origin.scheme()
            && current.port_or_known_default() == origin.port_or_known_default();
        // При проверке доступности ответ-перенаправление тоже означает, что сайт открывается.
        let follow = !req.discard_body;
        let mut builder = client.get(current.clone()).timeout(timeout);
        for (k, v) in &req.headers {
            if same_origin || !sensitive_header(k) {
                builder = builder.header(k, v);
            }
        }
        if same_origin {
            for (k, v) in &req.private_headers {
                builder = builder.header(k, v);
            }
            if let Some(host) = &req.host_header {
                builder = builder.header(reqwest::header::HOST, host);
            }
        }
        let resp = builder.send().await.map_err(describe)?;
        let status = resp.status();
        if status.is_redirection() && !follow {
            return Ok(FetchResponse { ok: true, status: status.as_u16(), final_url: Some(current.to_string()), ..Default::default() });
        }
        if status.is_redirection() {
            let location = resp
                .headers()
                .get(reqwest::header::LOCATION)
                .and_then(|v| v.to_str().ok())
                .ok_or("перенаправление без адреса")?;
            let next = current.join(location).map_err(|e| e.to_string())?;
            if current.scheme() == "https" && next.scheme() != "https" {
                return Err("перенаправление с https на http запрещено".into());
            }
            current = next;
            continue;
        }

        let mut headers = HashMap::new();
        for (name, value) in resp.headers() {
            headers
                .entry(name.as_str().to_ascii_lowercase())
                .or_insert_with(|| String::from_utf8_lossy(value.as_bytes()).into_owned());
        }
        let final_url = Some(current.to_string());
        let code = status.as_u16();

        if req.discard_body {
            return Ok(FetchResponse { ok: status.is_success() || status.is_client_error(), status: code, headers, final_url, ..Default::default() });
        }

        if let Some(path) = &req.out_file {
            if !status.is_success() {
                return Ok(FetchResponse { status: code, headers, final_url, error: Some(format!("HTTP {code}")), ..Default::default() });
            }
            let bytes = save_to_file(resp, path, req.max_bytes).await?;
            return Ok(FetchResponse { ok: true, status: code, headers, final_url, bytes, ..Default::default() });
        }

        let body = read_limited(resp, req.max_bytes).await?;
        let bytes = body.len() as u64;
        return Ok(FetchResponse {
            ok: status.is_success(),
            status: code,
            headers,
            body: Some(String::from_utf8_lossy(&body).into_owned()),
            final_url,
            bytes,
            ..Default::default()
        });
    }
    Err("слишком много перенаправлений".into())
}

pub(crate) async fn read_limited(mut resp: reqwest::Response, max: u64) -> Result<Vec<u8>, String> {
    if resp.content_length().is_some_and(|len| len > max) {
        return Err(too_big(max));
    }
    let mut out = Vec::with_capacity(resp.content_length().unwrap_or(16 * 1024).min(max).min(64 * 1024) as usize);
    while let Some(chunk) = resp.chunk().await.map_err(describe)? {
        if out.len() as u64 + chunk.len() as u64 > max {
            return Err(too_big(max));
        }
        out.extend_from_slice(&chunk);
    }
    Ok(out)
}

struct PartialDownload(String);

impl Drop for PartialDownload {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

/// Пишет во временный файл и атомарно подменяет целевой, чтобы ядро никогда не увидело недокачанный геофайл.
async fn save_to_file(mut resp: reqwest::Response, path: &str, max: u64) -> Result<u64, String> {
    if resp.content_length().is_some_and(|len| len > max) {
        return Err(too_big(max));
    }
    let tmp = format!("{path}.part");
    // Объявлен до файла: при отмене сначала закрывается файл, затем удаляется остаток.
    let _cleanup = PartialDownload(tmp.clone());
    let mut file = std::fs::File::create(&tmp).map_err(|e| e.to_string())?;
    let mut total = 0u64;
    let result: Result<(), String> = async {
        while let Some(chunk) = resp.chunk().await.map_err(describe)? {
            total += chunk.len() as u64;
            if total > max {
                return Err(too_big(max));
            }
            file.write_all(&chunk).map_err(|e| e.to_string())?;
        }
        file.sync_all().map_err(|e| e.to_string())
    }
    .await;
    drop(file);
    if let Err(e) = result {
        let _ = std::fs::remove_file(&tmp);
        return Err(e);
    }
    if total == 0 {
        let _ = std::fs::remove_file(&tmp);
        return Err("пустой ответ".into());
    }
    if let Err(e) = std::fs::rename(&tmp, path) {
        let _ = std::fs::remove_file(&tmp);
        return Err(e.to_string());
    }
    Ok(total)
}

fn too_big(max: u64) -> String {
    format!("ответ больше {} МБ", max / 1024 / 1024)
}

fn describe(e: reqwest::Error) -> String {
    let e = e.without_url();
    if e.is_timeout() {
        "сервер не ответил вовремя".into()
    } else if e.is_connect() {
        "не удалось подключиться к серверу".into()
    } else {
        let mut text = e.to_string();
        let mut source = std::error::Error::source(&e);
        while let Some(s) = source {
            text.push_str(": ");
            text.push_str(&s.to_string());
            source = s.source();
        }
        text
    }
}

#[cfg(test)]
mod tests {
    use super::cleartext_allowed;

    #[test]
    fn oversized_chunked_body_is_rejected() {
        use std::io::{Read, Write};
        let server = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = server.local_addr().unwrap();
        let thread = std::thread::spawn(move || {
            let (mut stream, _) = server.accept().unwrap();
            stream.set_read_timeout(Some(std::time::Duration::from_secs(5))).unwrap();
            let mut request = [0; 4096];
            stream.read(&mut request).unwrap();
            stream.write_all(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\nhello\r\n0\r\n\r\n").unwrap();
        });
        let req = serde_json::from_value(serde_json::json!({
            "url": format!("http://{address}/"), "maxBytes": 4, "timeoutMs": 1000
        })).unwrap();
        let result = super::fetch(req);
        assert!(!result.ok);
        assert!(result.error.unwrap().contains("ответ больше"));
        thread.join().unwrap();
    }

    #[test]
    fn partial_download_guard_removes_incomplete_file() {
        let path = std::env::temp_dir().join(format!("rvc-cancel-{}.part", std::process::id()));
        std::fs::write(&path, b"partial").unwrap();
        { let _guard = super::PartialDownload(path.to_string_lossy().into_owned()); }
        assert!(!path.exists());
    }

    #[test]
    fn redirect_chain_shares_one_timeout_budget() {
        use std::io::{Read, Write};
        let server = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = server.local_addr().unwrap();
        server.set_nonblocking(true).unwrap();
        let thread = std::thread::spawn(move || {
            let until = std::time::Instant::now() + std::time::Duration::from_secs(4);
            let mut count = 0;
            while count < 2 && std::time::Instant::now() < until {
                let (mut stream, _) = match server.accept() {
                    Ok(pair) => pair,
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(std::time::Duration::from_millis(10)); continue;
                    }
                    Err(e) => panic!("{e}"),
                };
                stream.set_read_timeout(Some(std::time::Duration::from_secs(2))).unwrap();
                let mut request = [0; 4096];
                let _ = stream.read(&mut request);
                std::thread::sleep(std::time::Duration::from_millis(700));
                let response = if count == 0 {
                    "HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                } else {
                    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                };
                let _ = stream.write_all(response.as_bytes());
                count += 1;
            }
        });
        let req = serde_json::from_value(serde_json::json!({
            "url": format!("http://{address}/"), "timeoutMs": 1000
        })).unwrap();
        let result = super::fetch(req);
        assert!(!result.ok, "redirect must not renew the timeout");
        assert!(result.error.unwrap().contains("вовремя"));
        thread.join().unwrap();
    }

    fn ok(u: &str) -> bool {
        cleartext_allowed(&reqwest::Url::parse(u).unwrap())
    }

    #[test]
    fn cleartext_only_inside_lan() {
        assert!(ok("https://sub.example.com/x"));
        assert!(ok("http://192.168.1.10:2096/sub"));
        assert!(ok("http://10.0.2.2:8080/s"));
        assert!(ok("http://127.0.0.1/s"));
        assert!(ok("http://[fd00::1]/s"));
        assert!(ok("http://router.local/s"));
        assert!(!ok("http://sub.example.com/x"));
        assert!(!ok("http://8.8.8.8/x"));
        assert!(!ok("http://[2001:db8::1]/x"));
        assert!(!ok("ftp://example.com/x"));
    }
}
