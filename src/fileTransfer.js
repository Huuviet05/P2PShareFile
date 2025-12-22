// fileTransfer.js
export function setupFileReceiver(dc, { onMeta, onProgress, onDone }) {
    let meta = null;
    let buffers = [];
    let received = 0;
  
    dc.binaryType = "arraybuffer";
  
    dc.onmessage = (e) => {
      // control as string
      if (typeof e.data === "string") {
        if (e.data.startsWith("META:")) {
          meta = JSON.parse(e.data.slice(5));
          buffers = [];
          received = 0;
          onMeta?.(meta);
          return;
        }
        if (e.data === "EOF") {
          const blob = new Blob(buffers, { type: meta?.type || "application/octet-stream" });
          const url = URL.createObjectURL(blob);
          onDone?.({ meta, blob, url });
          return;
        }
        return;
      }
  
      // binary chunk
      buffers.push(e.data);
      received += e.data.byteLength;
      if (meta?.size) {
        onProgress?.({ received, total: meta.size, pct: Math.floor((received / meta.size) * 100) });
      }
    };
  }
  
  export async function sendFileOverDataChannel(dc, file, { onProgress } = {}) {
    const CHUNK = 16 * 1024; // 16KB
    const MAX_BUFFERED = 4 * 1024 * 1024; // 4MB backpressure
  
    if (!dc || dc.readyState !== "open") throw new Error("DataChannel not open");
  
    // Send meta
    dc.send("META:" + JSON.stringify({
      name: file.name,
      size: file.size,
      type: file.type || "application/octet-stream",
    }));
  
    let offset = 0;
    while (offset < file.size) {
      const slice = file.slice(offset, offset + CHUNK);
      const buf = await slice.arrayBuffer();
  
      // backpressure
      while (dc.bufferedAmount > MAX_BUFFERED) {
        await new Promise(r => setTimeout(r, 20));
      }
  
      dc.send(buf);
      offset += buf.byteLength;
  
      onProgress?.({ sent: offset, total: file.size, pct: Math.floor((offset / file.size) * 100) });
    }
  
    dc.send("EOF");
  }
  