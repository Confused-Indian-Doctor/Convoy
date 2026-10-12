# Local convoy discovery and network bootstrap

Convoy advertises a bound TCP server with Android DNS-SD/mDNS as `_convoy._tcp.`.
The service's port is the socket's actual port; its resolved address belongs to the
current network, rather than a hardcoded hotspot gateway. The application works on
an ordinary shared LAN or a future OpenWrt/802.11s/BATMAN network where phones share
local IP connectivity. Internet connectivity is not required.

Public TXT metadata is deliberately limited to `v` (discovery version 1), `sid`
(canonical session UUID), `title`, `cars`, and `open`. The session UUID is a public
selector, not an authentication credential. A nearby advertisement cannot authorize
a driver: the current signed invitation and the encrypted transport admission must
still be verified by the live host. Discovery never publishes an invitation token,
Wi-Fi password, group encryption key or invitation secret.

`LanDiscovery` owns a non-reference-counted Wi-Fi multicast lock only while scanning
or advertising. It releases discovery, service callbacks, registration and network
callbacks when closed. Android 14 and newer use continuous service-info callbacks
for address/TXT changes. Older Android versions serialize bounded resolutions and
refresh after 30 seconds. A changed Wi-Fi link or an explicit reconnect triggers
fresh discovery. Repeated failures back off from two seconds to a maximum of 30
seconds. Nearby results are capped at 32 services. Invalid service metadata is
ignored; exception messages and payloads are not logged.

An invitation with local-hotspot bootstrap data can use `HotspotBootstrap` on
Android 10 and newer. Convoy requests the exact SSID and supplied WPA2/WPA3
credentials through `WifiNetworkSpecifier`; **Android** presents the connection
consent UI. The request removes the Internet capability and stays registered for
the joined convoy's lifetime. The returned `Network` binds convoy sockets, while
map downloads retain their ordinary routing. NSD is scoped to that network on
Android 13 and newer; older NSD browses available local interfaces. If the network
temporarily disappears, the request stays alive and Android can report its return.
The Service must close the helper when the trip ends.

Camera permission is independent of these helpers. Hotspot requests need Nearby
Wi-Fi permission on Android 13+, and precise Location with Location enabled on
Android 10–12. Normal permissions include network state, changing network state,
Wi-Fi state and changing Wi-Fi multicast state. A join must be initiated by an
explicit action while the app is visible. Android 8–9 cannot use the modern
specifier/consent API; those users need to connect through Android Wi-Fi settings
before local discovery. OEM restrictions, AP client isolation or a router blocking
multicast can prevent DNS-SD; Advanced manual local-IP connection remains a
diagnostic fallback. No UDP discovery fallback is included because no device test
has demonstrated the need for one.

The host's LocalOnlyHotspot must populate invitation bootstrap data from the
reservation's current platform configuration. An unavailable hotspot should offer
ordinary LAN mode rather than reveal network passwords in the normal UI.

`tools/run_discovery_tests.sh` tests the real public metadata parser, session/version
matching, dynamic advertised port changes, strict text validation, redaction,
bootstrap limits and retry backoff. It does not claim successful mDNS or hotspot
interoperability on physical devices. Those need a host and joiner phone across
supported Android/OEM versions, normal Wi-Fi and a no-Internet hotspot, including
network loss/recovery and multicast-blocked failure handling.

Primary Android API references:

- [Network Service Discovery](https://developer.android.com/develop/connectivity/wifi/use-nsd)
- [NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager)
- [Wi-Fi Network Request API](https://developer.android.com/develop/connectivity/wifi/wifi-bootstrap)

These helpers reuse Android's implementations and add no networking dependency.
