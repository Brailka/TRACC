//! Обрезка geosite.dat / geoip.dat до нужных тегов.
//!
//! Оба файла — protobuf-список `repeated Entry entry = 1`, где у каждой записи
//! первым полем идёт строка `country_code`. Файл отображается в память, записи
//! не декодируются: нужные куски байтов копируются в новый файл как есть.
//! Ядро затем загружает маленький файл и тратит в разы меньше памяти.

use memmap2::Mmap;
use std::collections::HashSet;
use std::fs::File;
use std::io::{BufWriter, Write};

fn read_varint(buf: &[u8], pos: &mut usize) -> Option<u64> {
    let mut result = 0u64;
    let mut shift = 0;
    loop {
        let byte = *buf.get(*pos)?;
        *pos += 1;
        result |= ((byte & 0x7F) as u64) << shift;
        if byte & 0x80 == 0 {
            return Some(result);
        }
        shift += 7;
        if shift > 63 {
            return None;
        }
    }
}

fn write_varint(out: &mut impl Write, mut value: u64) -> std::io::Result<()> {
    let mut buf = [0u8; 10];
    let mut i = 0;
    loop {
        let byte = (value & 0x7F) as u8;
        value >>= 7;
        if value == 0 {
            buf[i] = byte;
            i += 1;
            break;
        }
        buf[i] = byte | 0x80;
        i += 1;
    }
    out.write_all(&buf[..i])
}

/// Перебирает записи верхнего уровня: (тег записи, байты записи).
fn entries(buf: &[u8]) -> impl Iterator<Item = (String, &[u8])> {
    let mut pos = 0usize;
    std::iter::from_fn(move || loop {
        if pos >= buf.len() {
            return None;
        }
        let key = read_varint(buf, &mut pos)?;
        let wire = key & 7;
        match wire {
            2 => {
                let len = read_varint(buf, &mut pos)? as usize;
                let body = buf.get(pos..pos + len)?;
                pos += len;
                if key >> 3 == 1 {
                    return Some((code_of(body).unwrap_or_default(), body));
                }
            }
            0 => {
                read_varint(buf, &mut pos)?;
            }
            1 => pos += 8,
            5 => pos += 4,
            _ => return None,
        }
    })
}

fn code_of(entry: &[u8]) -> Option<String> {
    let mut pos = 0usize;
    while pos < entry.len() {
        let key = read_varint(entry, &mut pos)?;
        match key & 7 {
            2 => {
                let len = read_varint(entry, &mut pos)? as usize;
                let body = entry.get(pos..pos + len)?;
                if key >> 3 == 1 {
                    return Some(String::from_utf8_lossy(body).to_ascii_lowercase());
                }
                pos += len;
            }
            0 => {
                read_varint(entry, &mut pos)?;
            }
            1 => pos += 8,
            5 => pos += 4,
            _ => return None,
        }
    }
    None
}

fn map(path: &str) -> Result<Mmap, String> {
    let file = File::open(path).map_err(|e| format!("{path}: {e}"))?;
    // SAFETY: файл открыт только на чтение, а замена геофайлов делается через rename,
    // поэтому отображённые страницы не меняются под ногами.
    unsafe { Mmap::map(&file) }.map_err(|e| e.to_string())
}

/// Возвращает число сохранённых записей.
pub fn trim(src: &str, dst: &str, tags: &[String]) -> Result<u64, String> {
    let wanted: HashSet<String> = tags
        .iter()
        .map(|t| t.split('@').next().unwrap_or(t).trim().to_ascii_lowercase())
        .filter(|t| !t.is_empty())
        .collect();
    let data = map(src)?;
    let tmp = format!("{dst}.part");
    let mut out = BufWriter::with_capacity(256 * 1024, File::create(&tmp).map_err(|e| e.to_string())?);
    let mut kept = 0u64;
    let result: std::io::Result<()> = (|| {
        for (code, body) in entries(&data) {
            if wanted.contains(&code) {
                out.write_all(&[0x0A])?;
                write_varint(&mut out, body.len() as u64)?;
                out.write_all(body)?;
                kept += 1;
            }
        }
        out.flush()
    })();
    drop(out);
    if let Err(e) = result {
        let _ = std::fs::remove_file(&tmp);
        return Err(e.to_string());
    }
    std::fs::rename(&tmp, dst).map_err(|e| e.to_string())?;
    Ok(kept)
}

pub fn tags(src: &str) -> Result<Vec<String>, String> {
    let data = map(src)?;
    let mut list: Vec<String> = entries(&data).map(|(code, _)| code).filter(|c| !c.is_empty()).collect();
    list.sort();
    list.dedup();
    Ok(list)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(code: &str, payload: &[u8]) -> Vec<u8> {
        let mut inner = vec![0x0A, code.len() as u8];
        inner.extend_from_slice(code.as_bytes());
        inner.extend_from_slice(payload);
        let mut out = vec![0x0A, inner.len() as u8];
        out.extend(inner);
        out
    }

    #[test]
    fn keeps_only_requested_tags() {
        let dir = std::env::temp_dir();
        let src = dir.join("tracc-geo-src.dat");
        let dst = dir.join("tracc-geo-dst.dat");
        let mut data = entry("RU", &[0x12, 0x01, 0x41]);
        data.extend(entry("CN", &[0x12, 0x01, 0x42]));
        data.extend(entry("PRIVATE", &[]));
        std::fs::write(&src, &data).unwrap();
        let kept = trim(src.to_str().unwrap(), dst.to_str().unwrap(), &["ru".into(), "private@x".into()]).unwrap();
        assert_eq!(kept, 2);
        let out = std::fs::read(&dst).unwrap();
        let codes: Vec<String> = entries(&out).map(|(c, _)| c).collect();
        assert_eq!(codes, vec!["ru", "private"]);
        assert_eq!(tags(src.to_str().unwrap()).unwrap(), vec!["cn", "private", "ru"]);
    }
}
