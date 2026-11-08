//! Резолв адреса сервера через DNS-over-HTTPS (RFC 8484) до поднятия туннеля.
//!
//! Пакет собирается и разбирается вручную: нужны только записи A/AAAA, а
//! отдельная DNS-библиотека заметно увеличила бы размер APK.

use crate::runtime;
use serde::Deserialize;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::time::Duration;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DohRequest {
    pub name: String,
    pub doh_url: String,
    #[serde(default)]
    pub bootstrap_ip: Option<String>,
    #[serde(default = "default_timeout")]
    pub timeout_ms: u64,
    #[serde(default)]
    pub ipv6: bool,
}

fn default_timeout() -> u64 {
    5_000
}

pub fn resolve(req: DohRequest) -> Result<Vec<String>, String> {
    if let Ok(ip) = req.name.parse::<IpAddr>() {
        return Ok(vec![ip.to_string()]);
    }
    let url = reqwest::Url::parse(&req.doh_url).map_err(|e| e.to_string())?;
    if url.scheme() != "https" || !url.username().is_empty() || url.password().is_some() {
        return Err("Для DNS нужна ссылка https без логина и пароля".into());
    }
    let mut builder = reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .use_rustls_tls()
        .timeout(Duration::from_millis(req.timeout_ms.clamp(500, 20_000)));
    if let (Some(host), Some(ip)) = (url.host_str(), req.bootstrap_ip.as_deref().and_then(|s| s.parse::<IpAddr>().ok())) {
        builder = builder.resolve(host, SocketAddr::new(ip, 0));
    }
    let client = builder.build().map_err(|e| e.to_string())?;
    let qtype = if req.ipv6 { 28 } else { 1 };
    let query = build_query(&req.name, qtype)?;

    runtime().block_on(async move {
        let resp = client
            .post(url)
            .header("content-type", "application/dns-message")
            .header("accept", "application/dns-message")
            .body(query)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        if !resp.status().is_success() {
            return Err(format!("DoH HTTP {}", resp.status().as_u16()));
        }
        let bytes = crate::http::read_limited(resp, 65_535).await?;
        parse_answers(&bytes, qtype)
    })
}

fn build_query(name: &str, qtype: u16) -> Result<Vec<u8>, String> {
    if name.trim_end_matches('.').len() > 253 {
        return Err("слишком длинное имя DNS".into());
    }
    let mut out = Vec::with_capacity(32 + name.len());
    // ID = 0 рекомендован RFC 8484 для кэширования.
    out.extend_from_slice(&[0, 0, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0]);
    for label in name.trim_end_matches('.').split('.') {
        if label.is_empty() || label.len() > 63 {
            return Err("неверное имя".into());
        }
        out.push(label.len() as u8);
        out.extend_from_slice(label.as_bytes());
    }
    out.push(0);
    out.extend_from_slice(&qtype.to_be_bytes());
    out.extend_from_slice(&1u16.to_be_bytes());
    Ok(out)
}

fn skip_name(buf: &[u8], mut pos: usize) -> Option<usize> {
    loop {
        let len = *buf.get(pos)? as usize;
        if len == 0 {
            return Some(pos + 1);
        }
        if len & 0xC0 == 0xC0 {
            let low = *buf.get(pos + 1)? as usize;
            if ((len & 0x3F) << 8 | low) >= buf.len() { return None; }
            return Some(pos + 2);
        }
        if len > 63 { return None; }
        pos += 1 + len;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_plaintext_doh_before_connecting() {
        let req = DohRequest { name: "example.com".into(), doh_url: "http://127.0.0.1/dns-query".into(),
            bootstrap_ip: None, timeout_ms: 500, ipv6: false };
        assert!(resolve(req).unwrap_err().contains("https"));
    }

    #[test]
    fn rejects_oversized_names_and_bad_compression() {
        assert!(build_query(&vec!["a".repeat(63); 4].join("."), 1).is_err());
        assert!(skip_name(&[0xC0], 0).is_none());
        assert!(skip_name(&[0xC0, 0xFF], 0).is_none());
        assert!(skip_name(&[0x40, 0], 0).is_none());
    }

    #[test]
    fn accepts_answer_but_rejects_truncation_and_query_packets() {
        let mut packet = vec![0, 0, 0x81, 0x80, 0, 0, 0, 1, 0, 0, 0, 0,
            0, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2, 3, 4];
        assert_eq!(parse_answers(&packet, 1).unwrap(), vec!["1.2.3.4"]);
        packet[2] |= 2;
        assert!(parse_answers(&packet, 1).is_err());
        packet[2] = 1;
        assert!(parse_answers(&packet, 1).is_err());
    }
}

fn parse_answers(buf: &[u8], qtype: u16) -> Result<Vec<String>, String> {
    if buf.len() < 12 {
        return Err("короткий ответ DNS".into());
    }
    if buf[0..2] != [0, 0] || buf[2] & 0x80 == 0 || buf[2] & 0x7A != 0 {
        return Err("неверный или неполный ответ DNS".into());
    }
    let rcode = buf[3] & 0x0F;
    if rcode != 0 {
        return Err(format!("DNS rcode {rcode}"));
    }
    let qd = u16::from_be_bytes([buf[4], buf[5]]) as usize;
    let an = u16::from_be_bytes([buf[6], buf[7]]) as usize;
    let mut pos = 12;
    for _ in 0..qd {
        pos = skip_name(buf, pos).ok_or("битый ответ DNS")? + 4;
    }
    let mut ips = Vec::new();
    for _ in 0..an {
        pos = skip_name(buf, pos).ok_or("битый ответ DNS")?;
        let header = buf.get(pos..pos + 10).ok_or("битый ответ DNS")?;
        let rtype = u16::from_be_bytes([header[0], header[1]]);
        let rdlen = u16::from_be_bytes([header[8], header[9]]) as usize;
        pos += 10;
        let data = buf.get(pos..pos + rdlen).ok_or("битый ответ DNS")?;
        pos += rdlen;
        if rtype != qtype || header[2..4] != [0, 1] {
            continue;
        }
        match (rtype, rdlen) {
            (1, 4) => ips.push(Ipv4Addr::new(data[0], data[1], data[2], data[3]).to_string()),
            (28, 16) => {
                let mut octets = [0u8; 16];
                octets.copy_from_slice(data);
                ips.push(Ipv6Addr::from(octets).to_string());
            }
            _ => {}
        }
    }
    if ips.is_empty() {
        return Err("нет адресов в ответе".into());
    }
    Ok(ips)
}
