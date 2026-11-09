//! TCP- и ICMP-пинг серверов.
//!
//! Время резолва имени не входит в замер: сначала адрес определяется, потом
//! засекается только установка соединения. Для пакетного режима все замеры
//! идут на одном рантайме tokio с ограничением параллельности.

use crate::runtime;
use futures_util::stream::{self, StreamExt};
use serde::Deserialize;
use socket2::{Domain, Protocol, Socket, Type};
use std::net::{IpAddr, SocketAddr};
use std::time::{Duration, Instant};

#[derive(Deserialize)]
pub struct Target {
    pub host: String,
    pub port: u16,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BatchRequest {
    pub targets: Vec<Target>,
    #[serde(default = "default_timeout")]
    pub timeout_ms: u64,
    #[serde(default = "default_attempts")]
    pub attempts: u32,
    #[serde(default = "default_concurrency")]
    pub concurrency: usize,
}

fn default_timeout() -> u64 {
    2_500
}

fn default_attempts() -> u32 {
    2
}

fn default_concurrency() -> usize {
    32
}

async fn resolve(host: &str, port: u16) -> Option<SocketAddr> {
    if let Ok(ip) = host.trim_matches(|c| c == '[' || c == ']').parse::<IpAddr>() {
        return Some(SocketAddr::new(ip, port));
    }
    let mut addrs = tokio::time::timeout(Duration::from_secs(4), tokio::net::lookup_host((host, port)))
        .await
        .ok()?
        .ok()?
        .collect::<Vec<_>>();
    // IPv4 первым: так же поступает ядро с queryStrategy UseIPv4.
    addrs.sort_by_key(|a| !a.is_ipv4());
    addrs.into_iter().next()
}

async fn connect_once(addr: SocketAddr, timeout: Duration) -> Option<u64> {
    let started = Instant::now();
    match tokio::time::timeout(timeout, tokio::net::TcpStream::connect(addr)).await {
        Ok(Ok(stream)) => {
            let ms = started.elapsed().as_millis().max(1) as u64;
            drop(stream);
            Some(ms)
        }
        _ => None,
    }
}

async fn ping_target(target: &Target, timeout: Duration, attempts: u32) -> i64 {
    let Some(addr) = resolve(&target.host, target.port).await else {
        return -1;
    };
    let mut best: Option<u64> = None;
    for _ in 0..attempts.max(1) {
        if let Some(ms) = connect_once(addr, timeout).await {
            best = Some(best.map_or(ms, |b| b.min(ms)));
        }
    }
    best.map_or(-1, |ms| ms as i64)
}

pub fn tcp_batch(req: BatchRequest) -> Vec<i64> {
    let timeout = Duration::from_millis(req.timeout_ms.clamp(200, 15_000));
    let attempts = req.attempts.clamp(1, 5);
    let concurrency = req.concurrency.clamp(1, 128);
    runtime().block_on(async {
        let mut results = vec![-1i64; req.targets.len()];
        let mut pending = stream::iter(req.targets.iter().enumerate())
            .map(|(i, t)| async move { (i, ping_target(t, timeout, attempts).await) })
            .buffer_unordered(concurrency);
        while let Some((i, ms)) = pending.next().await {
            results[i] = ms;
        }
        results
    })
}

/// ICMP через «ping-сокеты» (SOCK_DGRAM + IPPROTO_ICMP), которые Android разрешает без root.
pub fn icmp(host: &str, timeout_ms: u64) -> i64 {
    let timeout = Duration::from_millis(timeout_ms.clamp(200, 10_000));
    let Some(addr) = runtime().block_on(resolve(host, 0)) else {
        return -1;
    };
    icmp_blocking(addr.ip(), timeout).unwrap_or(-1)
}

fn icmp_blocking(ip: IpAddr, timeout: Duration) -> Option<i64> {
    let (domain, proto, kind) = match ip {
        IpAddr::V4(_) => (Domain::IPV4, Protocol::ICMPV4, 8u8),
        IpAddr::V6(_) => (Domain::IPV6, Protocol::ICMPV6, 128u8),
    };
    let socket = Socket::new(domain, Type::DGRAM, Some(proto)).ok()?;
    socket.set_read_timeout(Some(timeout)).ok()?;
    socket.set_write_timeout(Some(timeout)).ok()?;

    let mut packet = [0u8; 16];
    packet[0] = kind;
    packet[6] = 0;
    packet[7] = 1;
    packet[8..16].copy_from_slice(b"traccping!");
    if kind == 8 {
        let sum = checksum(&packet);
        packet[2..4].copy_from_slice(&sum.to_be_bytes());
    }

    let started = Instant::now();
    socket.send_to(&packet, &SocketAddr::new(ip, 0).into()).ok()?;
    let mut buf = [std::mem::MaybeUninit::<u8>::uninit(); 128];
    loop {
        let (len, _) = socket.recv_from(&mut buf).ok()?;
        if started.elapsed() > timeout {
            return None;
        }
        // Ответ на эхо-запрос: тип 0 для IPv4, 129 для IPv6.
        let reply = unsafe { buf[0].assume_init() };
        if len >= 8 && (reply == 0 || reply == 129) {
            return Some(started.elapsed().as_millis().max(1) as i64);
        }
    }
}

fn checksum(data: &[u8]) -> u16 {
    let mut sum = 0u32;
    for chunk in data.chunks(2) {
        let word = u16::from_be_bytes([chunk[0], *chunk.get(1).unwrap_or(&0)]);
        sum = sum.wrapping_add(word as u32);
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}
