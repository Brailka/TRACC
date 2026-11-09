//! C ABI для iOS (и любых не-Android платформ). Объявления — `include/traccnet.h`.
//!
//! Строки на входе — UTF-8 с нулём в конце, их владелец — вызывающая сторона.
//! Строки на выходе выделяет Rust; их нужно вернуть через [`traccnet_free`].

use std::ffi::{c_char, CStr, CString};

fn arg(value: *const c_char) -> String {
    if value.is_null() {
        return String::new();
    }
    // SAFETY: вызывающая сторона передаёт корректную строку с нулём в конце.
    unsafe { CStr::from_ptr(value) }.to_string_lossy().into_owned()
}

fn out(value: String) -> *mut c_char {
    CString::new(value.replace('\0', "")).map(CString::into_raw).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn traccnet_fetch(request: *const c_char) -> *mut c_char {
    out(crate::fetch_json(&arg(request)))
}

#[no_mangle]
pub extern "C" fn traccnet_delay(request: *const c_char) -> i64 {
    crate::delay_json(&arg(request))
}

#[no_mangle]
pub extern "C" fn traccnet_tcp_ping(request: *const c_char) -> *mut c_char {
    out(crate::tcp_ping_json(&arg(request)))
}

#[no_mangle]
pub extern "C" fn traccnet_icmp_ping(host: *const c_char, timeout_ms: i64) -> i64 {
    crate::icmp_ping(&arg(host), timeout_ms)
}

#[no_mangle]
pub extern "C" fn traccnet_doh(request: *const c_char) -> *mut c_char {
    out(crate::doh_json(&arg(request)))
}

#[no_mangle]
pub extern "C" fn traccnet_trim_geo(src: *const c_char, dst: *const c_char, tags: *const c_char) -> i64 {
    crate::trim_geo(&arg(src), &arg(dst), &arg(tags))
}

#[no_mangle]
pub extern "C" fn traccnet_geo_tags(src: *const c_char) -> *mut c_char {
    out(crate::geo_tags(&arg(src)))
}

#[no_mangle]
pub extern "C" fn traccnet_version() -> *mut c_char {
    out(crate::version())
}

/// Освобождает строку, которую вернула любая функция `traccnet_*`.
#[no_mangle]
pub extern "C" fn traccnet_free(value: *mut c_char) {
    if !value.is_null() {
        // SAFETY: указатель получен из CString::into_raw в этом модуле.
        drop(unsafe { CString::from_raw(value) });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let v = traccnet_version();
        assert!(arg(v).starts_with("traccnet "));
        traccnet_free(v);

        let request = CString::new("not json").unwrap();
        let r = traccnet_fetch(request.as_ptr());
        assert!(arg(r).contains("\"ok\":false"));
        traccnet_free(r);

        assert_eq!(traccnet_delay(std::ptr::null()), -1);
        traccnet_free(std::ptr::null_mut());
    }
}
