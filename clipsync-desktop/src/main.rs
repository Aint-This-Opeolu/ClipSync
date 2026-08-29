mod clipboard_watch;
mod crypto;
mod discovery;
mod protocol;

use clipboard_watch::SharedClip;
use mdns_sd::ServiceDaemon;
use protocol::{ClipUpdate, Hello};
use std::io::Write;
use std::sync::{Arc, Mutex};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, mpsc};

const PORT: u16 = 53211;

fn banner() {
    println!("========================================================");
    println!(" Kenechkwu Ozobial Opeolu");
    println!(" Reg No: 2022514154");
    println!(" Final Year Project (ND) - LAN Clipboard Sync (desktop)");
    println!("========================================================\n");
}

fn prompt(label: &str) -> String {
    print!("{label}: ");
    std::io::stdout().flush().unwrap();
    let mut s = String::new();
    std::io::stdin().read_line(&mut s).unwrap();
    s.trim().to_string()
}

#[tokio::main]
async fn main() {
    banner();

    let pairing_code = prompt("Pairing code (enter the same on both devices)");
    let device_name = {
        let entered = prompt("Device name [default: this-laptop]");
        if entered.is_empty() { "this-laptop".to_string() } else { entered }
    };

    let key = crypto::derive_key(&pairing_code);
    let fingerprint = crypto::key_fingerprint(&key);
    println!("Key fingerprint: {fingerprint}  (should match on both devices)\n");

    let shared_clip: SharedClip = Arc::new(Mutex::new(None));
    let (tx_local_change, mut rx_local_change) = mpsc::channel::<String>(16);

    // Poll the local clipboard for outgoing changes.
    tokio::spawn(clipboard_watch::watch_local_clipboard(
        shared_clip.clone(),
        tx_local_change,
    ));

    // Fan local clipboard changes out to every active connection.
    let (tx_broadcast, _) = broadcast::channel::<String>(16);
    {
        let tx_broadcast = tx_broadcast.clone();
        tokio::spawn(async move {
            while let Some(text) = rx_local_change.recv().await {
                let _ = tx_broadcast.send(text); // Err just means no peer connected yet
            }
        });
    }

    // mDNS: advertise ourselves, and browse for a matching peer in parallel.
    let mdns = ServiceDaemon::new().expect("start mdns daemon");
    let self_fullname = discovery::advertise(&mdns, &device_name, PORT, &fingerprint);
    println!("Advertising on the LAN as '{device_name}', listening on port {PORT}.");

    let listener = match TcpListener::bind(("0.0.0.0", PORT)).await {
        Ok(l) => l,
        Err(e) => {
            eprintln!(
                "Could not bind port {PORT}: {e}\n\
                 Likely another instance of this app is already running on this machine. \
                 Only one instance can run per device."
            );
            std::process::exit(1);
        }
    };

    // Accept incoming connections (peer connected to us first).
    {
        let key = key;
        let fp = fingerprint.clone();
        let name = device_name.clone();
        let shared_clip = shared_clip.clone();
        let tx_broadcast = tx_broadcast.clone();
        tokio::spawn(async move {
            loop {
                if let Ok((stream, addr)) = listener.accept().await {
                    println!("Incoming connection from {addr}");
                    tokio::spawn(handle_connection(
                        stream,
                        key,
                        fp.clone(),
                        name.clone(),
                        shared_clip.clone(),
                        tx_broadcast.subscribe(),
                    ));
                }
            }
        });
    }

    // Actively browse and dial a matching peer (we connect to them first).
    // Retries periodically in case the peer app opens after this one.
    {
        let key = key;
        let fp = fingerprint.clone();
        let name = device_name.clone();
        let shared_clip = shared_clip.clone();
        let tx_broadcast = tx_broadcast.clone();
        tokio::spawn(async move {
            loop {
                let fp2 = fp.clone();
                let self_fullname2 = self_fullname.clone();
                let mdns_daemon = ServiceDaemon::new().expect("start mdns daemon (dial)");
                let peer = tokio::task::spawn_blocking(move || {
                    discovery::browse_for_matching_peer(&mdns_daemon, &fp2, &self_fullname2)
                })
                .await
                .ok()
                .flatten();

                if let Some(peer) = peer {
                    println!("Found matching peer at {}:{}", peer.addr, peer.port);
                    if let Ok(stream) = TcpStream::connect((peer.addr, peer.port)).await {
                        handle_connection(
                            stream,
                            key,
                            fp.clone(),
                            name.clone(),
                            shared_clip.clone(),
                            tx_broadcast.subscribe(),
                        )
                        .await;
                    }
                }

                tokio::time::sleep(std::time::Duration::from_secs(5)).await;
            }
        });
    }

    println!("\nWaiting for a paired connection... (open the Android app and enter the same pairing code)\n");

    // Keep the process alive.
    std::future::pending::<()>().await;
}

async fn handle_connection(
    mut stream: TcpStream,
    key: [u8; 32],
    my_fingerprint: String,
    my_name: String,
    shared_clip: SharedClip,
    mut outgoing: broadcast::Receiver<String>,
) {
    let hello = Hello {
        device_name: my_name.clone(),
        key_fingerprint: my_fingerprint.clone(),
    };
    if protocol::send_hello(&mut stream, &hello).await.is_err() {
        return;
    }
    let peer_hello = match protocol::read_hello(&mut stream).await {
        Ok(Some(h)) => h,
        _ => return,
    };
    if peer_hello.key_fingerprint != my_fingerprint {
        eprintln!(
            "Rejected connection from '{}': pairing code mismatch.",
            peer_hello.device_name
        );
        return;
    }
    println!("Paired with '{}'.", peer_hello.device_name);

    let (mut read_half, mut write_half) = stream.into_split();

    let writer_task = tokio::spawn(async move {
        loop {
            match outgoing.recv().await {
                Ok(text) => {
                    let update = ClipUpdate { text, from_device: my_name.clone() };
                    let json = serde_json::to_vec(&update).unwrap();
                    let encrypted = crypto::encrypt(&key, &json);
                    if protocol::write_frame(&mut write_half, &encrypted).await.is_err() {
                        break;
                    }
                }
                Err(broadcast::error::RecvError::Lagged(_)) => continue,
                Err(broadcast::error::RecvError::Closed) => break,
            }
        }
    });

    loop {
        match protocol::read_frame(&mut read_half).await {
            Ok(Some(blob)) => {
                if let Some(plaintext) = crypto::decrypt(&key, &blob) {
                    if let Ok(update) = serde_json::from_slice::<ClipUpdate>(&plaintext) {
                        clipboard_watch::apply_remote_update(&shared_clip, &update.text);
                        println!("Synced clipboard from '{}'.", update.from_device);
                    }
                }
            }
            _ => break,
        }
    }

    writer_task.abort();
}
