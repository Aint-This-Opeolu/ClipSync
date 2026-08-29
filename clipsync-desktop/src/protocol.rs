use serde::{Deserialize, Serialize};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

/// Handshake message, sent unencrypted first so both sides can confirm
/// they were paired with the same code before trusting the connection.
#[derive(Serialize, Deserialize)]
pub struct Hello {
    pub device_name: String,
    pub key_fingerprint: String,
}

/// A clipboard update, sent encrypted after a successful handshake.
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ClipUpdate {
    pub text: String,
    pub from_device: String,
}

/// Writes a length-prefixed frame: [4-byte BE length][payload].
pub async fn write_frame<W: AsyncWrite + Unpin>(w: &mut W, payload: &[u8]) -> std::io::Result<()> {
    let len = payload.len() as u32;
    w.write_all(&len.to_be_bytes()).await?;
    w.write_all(payload).await?;
    w.flush().await
}

/// Reads a length-prefixed frame. Returns None on clean EOF.
pub async fn read_frame<R: AsyncRead + Unpin>(r: &mut R) -> std::io::Result<Option<Vec<u8>>> {
    let mut len_buf = [0u8; 4];
    match r.read_exact(&mut len_buf).await {
        Ok(_) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let len = u32::from_be_bytes(len_buf) as usize;

    // Sanity cap: clipboard payloads shouldn't realistically exceed a few MB.
    const MAX_FRAME: usize = 16 * 1024 * 1024;
    if len > MAX_FRAME {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "frame too large",
        ));
    }

    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf).await?;
    Ok(Some(buf))
}

pub async fn send_hello<W: AsyncWrite + Unpin>(w: &mut W, hello: &Hello) -> std::io::Result<()> {
    let json = serde_json::to_vec(hello).expect("serialize hello");
    write_frame(w, &json).await
}

pub async fn read_hello<R: AsyncRead + Unpin>(r: &mut R) -> std::io::Result<Option<Hello>> {
    match read_frame(r).await? {
        Some(bytes) => Ok(serde_json::from_slice(&bytes).ok()),
        None => Ok(None),
    }
}
