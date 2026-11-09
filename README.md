# TRACC

Клиент для Android и Windows для VPN-сервисов на панели [Remnawave](https://docs.rw) и совместимых подписок Happ/INCY.
Минималистичный интерфейс в стиле liquid glass, одна большая кнопка, информация о подписке и объявления провайдера прямо на главном экране.

Лицензия — GPL-3.0. Чат: https://t.me/traccapp

## Протоколы

VLESS (Reality, XTLS-Vision, xhttp/splithttp, ws, grpc, httpupgrade, mKCP), VMess, Trojan, Shadowsocks (вкл. 2022),
Hysteria2 (obfs salamander, port hopping), SOCKS5, HTTP/HTTPS-прокси, WireGuard (через Xray), AmneziaWG 1.5/2.0/3.x (amneziawg-go v3.1).
Полные JSON-конфигурации Xray (Remnawave XRAY_JSON) с балансировщиками и observatory.

| Компонент | Версия |
| :-- | :-- |
| Xray-core (AndroidLibXrayLite) | 26.10.21 |
| amneziawg-go (из amneziawg-android) | 3.1.20260814 |
| Сетевой модуль на Rust (`rust/traccnet`) | reqwest + rustls, tokio, memmap2 |

## Совместимость с Remnawave, Happ и INCY

- **HWID всегда отправляется**: `x-hwid`, `x-device-os`, `x-ver-os`, `x-device-model`, `User-Agent: TRACC/<версия>` (в Remnawave добавьте Response Rule `user-agent CONTAINS TRACC → XRAY_JSON`, чтобы работали автовыбор и балансировщики). Работает лимит устройств Remnawave; ответы `x-hwid-max-devices-reached` / `x-hwid-not-supported` показываются пользователю.
- Форматы подписки: base64, plain text, JSON-массив конфигураций Xray, `.conf` WireGuard/AmneziaWG, `amneziawg://`/`awg://`, JSON-контейнер AmneziaWG от INCY.
- Заголовки и строки `#key: value` в теле: `profile-title` (в т.ч. `base64:` с описанием), `profile-description`, `subscription-userinfo` (включая `0` — скрыть), `profile-update-interval`, `support-url`, `support-email`, `profile-web-page-url`/`homepage`, `announce`, `announce-url`, `premium-url`, `banner-*`, `expiry-banner-*`, `sub-info-*`, `sub-expire`, `notification-subs-expire`, `hide-url`, `hide-check`, `new-url`, `new-domain`, `fallback-url`, `routing`, `autorouting`, `routing-enable`, `sort-order`/`subscriptions-sort-type`, `per-app-proxy-*`, `fragmentation-*`, `noises-*`, `server-address-resolve-*`, `subscription-autoconnect(-type)`, `subscription-ping-onopen-enabled`, `subscription-auto-update-(open-)enable`, `ping-type`, `check-url-via-proxy`, `mux-*`, `sniffing-enable`, `exclude-routes(-set)`, `xray-tun-mtu`, `hide-vpn-icon`, `providerid`, `subscription-request-timeout`, `dont-use-filter`, метки серверов `only Wi-Fi` / `only Mobile`, описание сервера `#Имя?serverDescription=base64`.
- Логотип провайдера: заголовок `profile-logo-url` (https, PNG/JPEG/WebP).
- Push-уведомления провайдера без Firebase: заголовок `notifications-url` → JSON `[{"id","title","body","url"}]`, опрашивается в фоне; плюс уведомления о новых объявлениях и об окончании подписки.

### Маршрутизация (как в Happ/INCY)

Профили в формате routing.happ.su: `GlobalProxy`, `RemoteDNS*`/`DomesticDNS*` (DoH/DoU), `DnsHosts`, `DirectSites/DirectIp`, `ProxySites/ProxyIp`, `BlockSites/BlockIp`, `DomainStrategy`, `FakeDNS`, `Geoipurl`/`Geositeurl` (с проверкой `.sha256`), `LastUpdated`, `RouteOrder`.
Ссылки: `://routing/add|onadd/{base64|url}`, `://autorouting/add|onadd/{url}`, `://onadd/…`, `://routing/off`, `?data=`. Профиль из подписки привязывается к ней и удаляется вместе с ней.
Встроенные профили: «Россия напрямую» и «Весь трафик через VPN».

### Deep links

Схемы `tracc://`, `happ://`, `incy://`, `v2raytun://`, `sub://` и прямые `vless://`, `vmess://`, `trojan://`, `ss://`, `hy2://`, `hysteria2://`, `socks://`, `wireguard://`, `awg://`:
`add/{url}`, `import/{данные}`, `routing/…`, `autorouting/…`, `connect`/`open`, `disconnect`/`close`, `toggle`, `status`, `incy://crypt1/…`.

## Безопасность и необнаружимость

- **Нет открытых портов на 127.0.0.1.** Трафик идёт через встроенный TUN inbound Xray (дескриптор передаётся в ядро), без SOCKS/HTTP-inbound'ов — другие приложения не могут найти прокси сканированием портов и узнать через него IP сервера. API и метрики ядра вырезаются даже из конфигураций провайдера. Единственное исключение — загрузка подписки через туннель, когда её адрес заблокирован: отдельный экземпляр ядра поднимает SOCKS на случайном порту со случайным логином и паролем ровно на время запроса.
- DNS из туннеля перехватывается ядром (DoH через прокси для зарубежных ресурсов), IPv6 всегда маршрутизируется в туннель — без DNS- и IPv6-утечек.
- Kill switch: при сбое интерфейс VPN не снимается и трафик блокируется; поддержка системных «Постоянная VPN» и «Блокировать соединения без VPN».
- MTU 1500 и случайная подсеть TUN вместо «фирменных» адресов других клиентов.
- Раздельное туннелирование по приложениям: банки и госуслуги можно вывести из VPN — они работают через обычную сеть.
- Подписки, ключи серверов, профили и выбранный сервер хранятся зашифрованными (AES-256-GCM, ключ в Android Keystore); резервное копирование данных приложения отключено; в release-сборке логи вырезаются.

## Сборка

Нужны JDK 17+, Android SDK (platform 36, build-tools 36), NDK 27.2.12479018, Rust с `cargo-ndk`.

```sh
./scripts/fetch-natives.sh      # libv2ray.aar, libwg-go.so
./scripts/build-rust.sh         # libtraccnet.so для 4 ABI
./gradlew :app:testDebugUnitTest :app:assembleRelease \
  -PtraccVersion=1.0.0 -PtraccUpdateRepo=OWNER/TRACC
```

Подпись релиза — `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) или переменные `TRACC_KEYSTORE*`.
Плашка обновления читает `update.json` из GitHub Releases репозитория, переданного через
`-PtraccUpdateRepo=owner/repository`. Workflow `.github/workflows/release.yml` собирает и публикует
APK, контрольные суммы, `update.json` и архив исходников при отправке тега `vX.Y.Z`.

### Windows

Модуль `desktop/` — Compose Multiplatform поверх `shared`; ядро `tracc-core.exe` (Go + Xray-core), сеть — `traccnet.dll` через JNA.
Нужны JDK 21, Go 1.27 и Rust с целью `x86_64-pc-windows-gnu`. Подробности — `desktop/README.md`, UX и замеры — `desktop/UX-AUDIT.md`.

```sh
desktop/scripts/build-natives.sh                               # tracc-core.exe, traccnet.dll, wintun.dll, геобазы
./gradlew :desktop:test :desktop:packageMsi :desktop:packageExe -PtraccDesktopVersion=1.0.0
python scripts/make-windows-update.py 1.0.0 dist OWNER/TRACC notes-windows.txt   # update-windows.json для релиза windows-v1.0.0
```

## Лицензии

TRACC — GPL-3.0. Сторонние компоненты: Фарватер (GPL-3.0), Xray-core и AndroidLibXrayLite (MPL-2.0), amneziawg-go / amneziawg-android (MIT / Apache-2.0), Compose Multiplatform и JNA (Apache-2.0), Wintun (распространение неизменённой DLL по лицензии WireGuard LLC, `LICENSE-Wintun.txt`), reqwest, rustls, tokio, memmap2, serde (MIT / Apache-2.0), шрифт Inter (OFL-1.1), ключ ссылок crypt1 из `@incy/link-encoder` (MIT, © INCY LLC).
