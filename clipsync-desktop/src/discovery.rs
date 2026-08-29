use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use std::collections::HashSet;
use std::net::IpAddr;

pub const SERVICE_TYPE: &str = "_clipsync._tcp.local.";

pub struct DiscoveredPeer {
    pub addr: IpAddr,
    pub port: u16,
}

/// Advertises this device on the LAN so peers can find it. Returns our own
/// fully-qualified service name, exactly as mdns-sd will report it back to
/// peers browsing the network, so callers can reliably filter themselves
/// out of their own browse results (see `browse_for_matching_peer`).
pub fn advertise(daemon: &ServiceDaemon, device_name: &str, port: u16, fingerprint: &str) -> String {
    let host_name = format!("{}.local.", device_name.replace(' ', "-"));
    let properties = [("fp", fingerprint)];

    let service = ServiceInfo::new(
        SERVICE_TYPE,
        device_name,
        &host_name,
        "",
        port,
        &properties[..],
    )
    .expect("build mdns service info")
    .enable_addr_auto();

    let fullname = service.get_fullname().to_string();

    daemon
        .register(service)
        .expect("register mdns service");

    fullname
}

/// Browses for peers advertising a matching key fingerprint. Blocks the
/// current (blocking-friendly) thread reading events; intended to be run
/// inside `tokio::task::spawn_blocking`. `self_fullname` must be the exact
/// string returned by `advertise`, used for an exact (not prefix) match so
/// devices with similar names can't be mistaken for ourselves.
pub fn browse_for_matching_peer(
    daemon: &ServiceDaemon,
    fingerprint: &str,
    self_fullname: &str,
) -> Option<DiscoveredPeer> {
    let receiver = daemon.browse(SERVICE_TYPE).expect("browse mdns");
    let mut seen: HashSet<String> = HashSet::new();

    while let Ok(event) = receiver.recv() {
        if let ServiceEvent::ServiceResolved(info) = event {
            let name = info.get_fullname().to_string();
            if !seen.insert(name.clone()) {
                continue;
            }
            if name == self_fullname {
                continue; // don't connect to ourselves
            }

            let matches = info
                .get_property_val_str("fp")
                .map(|fp| fp == fingerprint)
                .unwrap_or(false);

            if !matches {
                continue;
            }

            if let Some(addr) = info.get_addresses().iter().next() {
                return Some(DiscoveredPeer {
                    addr: *addr,
                    port: info.get_port(),
                });
            }
        }
    }
    None
}
