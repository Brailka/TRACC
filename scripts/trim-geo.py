#!/usr/bin/env python3
"""Урезает geoip.dat/geosite.dat из libv2ray.aar до популярных тегов для встраивания в APK.

Использование: trim-geo.py <assets-dir-from-aar> <app/src/main/assets>
"""
import os, sys

GEOIP = """ru private by kz ua am az ge md uz kg tj tm
telegram google cloudflare cloudfront facebook netflix twitter fastly""".split()
GEOSITE = """private category-ru category-gov-ru category-bank-ru category-media-ru category-ecommerce-ru
category-retail-ru category-education-ru category-entertainment-ru category-forums-ru category-medicine-ru
category-travel-ru category-tech-media-ru category-ai-ru category-media-ru-blocked tld-ru mailru mailru-group yandex vk
tbank-ru mts-ru t2-ru rutube autoru category-ads category-ads-and-telemetry
google google-play youtube telegram whatsapp instagram meta facebook twitter discord openai netflix microsoft
apple steam github spotify tiktok category-proxy-tunnels""".split()

def varint(b, p):
    r = s = 0
    while True:
        c = b[p]; p += 1; r |= (c & 0x7F) << s
        if c < 0x80: return r, p
        s += 7

def enc(v):
    out = bytearray()
    while True:
        b = v & 0x7F; v >>= 7
        if v: out.append(b | 0x80)
        else: out.append(b); return bytes(out)

def trim(src, dst, keep):
    keep = set(keep); b = open(src, 'rb').read(); p = 0; out = bytearray(); found = set()
    while p < len(b):
        _, p = varint(b, p); l, p = varint(b, p); e = b[p:p + l]; p += l
        q = 0; _, q = varint(e, q); l2, q = varint(e, q); code = e[q:q + l2].decode().lower()
        if code in keep:
            out += b'\x0a' + enc(len(e)) + e; found.add(code)
    open(dst, 'wb').write(out)
    print(f"{os.path.basename(dst)}: {len(found)} тегов, {len(out) // 1024} КБ; нет: {sorted(keep - found)}")

src, dst = sys.argv[1], sys.argv[2]
trim(os.path.join(src, 'geoip.dat'), os.path.join(dst, 'geoip.dat'), GEOIP)
trim(os.path.join(src, 'geosite.dat'), os.path.join(dst, 'geosite.dat'), GEOSITE)
