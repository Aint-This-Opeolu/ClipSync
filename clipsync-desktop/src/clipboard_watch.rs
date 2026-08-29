use arboard::Clipboard;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc::Sender;

const POLL_INTERVAL: Duration = Duration::from_millis(600);

/// Shared belief of "what the clipboard currently holds", updated both
/// when we detect a local change and when we apply a remote one. This
/// single source of truth is what prevents send/receive echo loops.
pub type SharedClip = Arc<Mutex<Option<String>>>;

/// Polls the local clipboard for changes and pushes new text onto `tx`.
/// Runs until the process exits; intended to run in its own task.
pub async fn watch_local_clipboard(shared: SharedClip, tx: Sender<String>) {
    let mut clipboard = match Clipboard::new() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("Could not access system clipboard: {e}");
            return;
        }
    };

    loop {
        tokio::time::sleep(POLL_INTERVAL).await;

        let current = match clipboard.get_text() {
            Ok(t) => t,
            Err(_) => continue, // e.g. clipboard holds a non-text item, skip
        };

        let changed = {
            let mut guard = shared.lock().unwrap();
            let changed = guard.as_deref() != Some(current.as_str());
            if changed {
                *guard = Some(current.clone());
            }
            changed
        }; // guard dropped here, before the await below

        if changed && tx.send(current).await.is_err() {
            return; // receiver gone, connection closed
        }
    }
}

/// Applies a clipboard update received from a peer, and updates the
/// shared state so the next local poll doesn't re-send it.
pub fn apply_remote_update(shared: &SharedClip, text: &str) {
    if let Ok(mut clipboard) = Clipboard::new() {
        if clipboard.set_text(text.to_string()).is_ok() {
            *shared.lock().unwrap() = Some(text.to_string());
        }
    }
}
