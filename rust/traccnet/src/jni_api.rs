//! JNI-обвязка для Android: класс `app.tracc.net.RustNet`.

use crate::*;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;

fn input(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value).map(|s| s.into()).unwrap_or_default()
}

fn output(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_app_tracc_net_RustNet_nativeFetch(mut env: JNIEnv, _class: JClass, request: JString) -> jstring {
    let request = input(&mut env, &request);
    let result = fetch_json(&request);
    output(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_app_tracc_net_RustNet_nativeDelay(mut env: JNIEnv, _class: JClass, request: JString) -> jlong {
    let request = input(&mut env, &request);
    delay_json(&request)
}

#[no_mangle]
pub extern "system" fn Java_app_tracc_net_RustNet_nativeTcpPing(mut env: JNIEnv, _class: JClass, request: JString) -> jstring {
    let request = input(&mut env, &request);
    let result = tcp_ping_json(&request);
    output(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_app_tracc_net_RustNet_nativeIcmpPing(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    timeout_ms: jlong,
) -> jlong {
    let host = input(&mut env, &host);
    icmp_ping(&host, timeout_ms)
}

#[no_mangle]
pub extern "system" fn Java_app_tracc_net_RustNet_nativeDoh(mut env: JNIEnv, _class: JClass, request: JString) -> jstring {
    let request = input(&mut env, &request);
    let result = doh_json(&request);
    output(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_app_tracc_net_RustNet_nativeTrimGeo(
    mut env: JNIEnv,
    _class: JClass,
    src: JString,
    dst: JString,
    tags: JString,
) -> jlong {
    let src = input(&mut env, &src);
    let dst = input(&mut env, &dst);
    let tags = input(&mut env, &tags);
    trim_geo(&src, &dst, &tags)
}

#[no_mangle]
pub extern "system" fn Java_app_tracc_net_RustNet_nativeGeoTags(mut env: JNIEnv, _class: JClass, src: JString) -> jstring {
    let src = input(&mut env, &src);
    let result = geo_tags(&src);
    output(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_app_tracc_net_RustNet_nativeVersion(mut env: JNIEnv, _class: JClass) -> jstring {
    output(&mut env, version())
}
