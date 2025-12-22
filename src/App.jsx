import { useRef, useState } from "react";

export default function App() {
  const pcRef = useRef(null);
  const dcRef = useRef(null);
  const fileMetaRef = useRef(null);
  const fileChunksRef = useRef([]);

  const [mode, setMode] = useState(null);
  const [localSDP, setLocalSDP] = useState("");
  const [remoteSDP, setRemoteSDP] = useState("");
  const [displayPin, setDisplayPin] = useState("");
  const [inputPin, setInputPin] = useState("");
  const [status, setStatus] = useState("idle");
  const [isConnected, setIsConnected] = useState(false);
  const [selectedFile, setSelectedFile] = useState(null);

  const createPC = () => {
    const pc = new RTCPeerConnection({
      iceServers: [{ urls: "stun:stun.l.google.com:19302" }]
    });

    pc.onicecandidate = (e) => {
      if (!e.candidate) {
        const sdp = JSON.stringify(pc.localDescription);
        setLocalSDP(sdp);
        const pin = btoa(sdp).substring(0, 6).toUpperCase();
        setDisplayPin(pin);
      }
    };

    pc.ondatachannel = (e) => {
      dcRef.current = e.channel;
      setupDC();
    };

    pcRef.current = pc;
    return pc;
  };

  const setupDC = () => {
    const dc = dcRef.current;
    dc.binaryType = "arraybuffer";

    dc.onopen = () => {
      setStatus("Sẵn sàng truyền file!");
      setIsConnected(true);
    };

    dc.onmessage = (e) => {
      if (typeof e.data === "string") {
        if (e.data === "EOF") {
          saveFile();
        } else {
          fileMetaRef.current = JSON.parse(e.data);
          fileChunksRef.current = [];
          setStatus(`Đang nhận: ${JSON.parse(e.data).name}`);
        }
        return;
      }
      fileChunksRef.current.push(e.data);
    };
  };

  const saveFile = () => {
    const meta = fileMetaRef.current;
    const blob = new Blob(fileChunksRef.current);
    const url = URL.createObjectURL(blob);

    const a = document.createElement("a");
    a.href = url;
    a.download = meta.name;
    a.click();

    URL.revokeObjectURL(url);
    setStatus("Đã tải xuống file!");
  };

  const createOffer = async () => {
    const pc = createPC();
    dcRef.current = pc.createDataChannel("file");
    setupDC();

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    setStatus("Đang chờ kết nối...");
  };

  const setRemoteOffer = async (sdp) => {
    const pc = pcRef.current || createPC();
    await pc.setRemoteDescription(JSON.parse(sdp));
    setStatus("Đã nhận offer");
  };

  const createAnswer = async () => {
    const pc = pcRef.current;
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    setStatus("Đã tạo answer");
  };

  const setRemoteAnswer = async (sdp) => {
    await pcRef.current.setRemoteDescription(JSON.parse(sdp));
    setStatus("Đã kết nối!");
    setIsConnected(true);
  };

  const sendFile = async (file) => {
    if (!file) return;

    const dc = dcRef.current;
    const chunk = 16 * 1024;
    let offset = 0;

    setStatus(`Đang gửi: ${file.name}`);
    dc.send(JSON.stringify({ name: file.name, size: file.size }));

    while (offset < file.size) {
      const buf = await file.slice(offset, offset + chunk).arrayBuffer();
      dc.send(buf);
      offset += buf.byteLength;
    }
    dc.send("EOF");
    setStatus("Đã gửi xong!");
  };

  const startSendMode = () => {
    setMode('send');
    createOffer();
  };

  const startReceiveMode = () => {
    setMode('receive');
  };

  const connectToSender = async () => {
    if (!inputPin) return;
    await setRemoteOffer(inputPin);
    await createAnswer();
  };

  const connectToReceiver = async () => {
    if (!inputPin) return;
    await setRemoteAnswer(inputPin);
  };

  const styles = {
    container: {
      minHeight: '100vh',
      background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      padding: '20px',
      fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif'
    },
    card: {
      background: 'white',
      borderRadius: '20px',
      padding: '40px',
      boxShadow: '0 20px 60px rgba(0,0,0,0.3)',
      maxWidth: '500px',
      width: '100%'
    },
    title: {
      fontSize: '42px',
      fontWeight: 'bold',
      textAlign: 'center',
      margin: '0 0 10px 0',
      color: '#1a202c'
    },
    subtitle: {
      textAlign: 'center',
      color: '#718096',
      marginBottom: '40px'
    },
    button: {
      width: '100%',
      padding: '20px',
      background: 'white',
      border: '3px solid transparent',
      borderRadius: '16px',
      cursor: 'pointer',
      transition: 'all 0.3s ease',
      marginBottom: '20px',
      boxShadow: '0 4px 15px rgba(0,0,0,0.1)'
    },
    icon: {
      fontSize: '48px',
      marginBottom: '15px',
      display: 'block',
      textAlign: 'center'
    },
    buttonTitle: {
      fontSize: '24px',
      fontWeight: 'bold',
      textAlign: 'center',
      margin: '10px 0',
      color: '#1a202c'
    },
    buttonDesc: {
      textAlign: 'center',
      color: '#718096',
      fontSize: '14px'
    },
    sdpBox: {
      background: '#f7fafc',
      borderRadius: '12px',
      padding: '15px',
      marginBottom: '20px',
      fontSize: '12px',
      fontFamily: 'monospace',
      wordBreak: 'break-all',
      maxHeight: '120px',
      overflowY: 'auto',
      border: '2px solid #e2e8f0'
    },
    input: {
      width: '100%',
      padding: '15px',
      fontSize: '14px',
      border: '2px solid #e2e8f0',
      borderRadius: '12px',
      outline: 'none',
      marginBottom: '15px',
      boxSizing: 'border-box',
      fontFamily: 'monospace'
    },
    textarea: {
      width: '100%',
      padding: '15px',
      fontSize: '12px',
      border: '2px solid #e2e8f0',
      borderRadius: '12px',
      outline: 'none',
      marginBottom: '15px',
      boxSizing: 'border-box',
      fontFamily: 'monospace',
      resize: 'vertical',
      minHeight: '100px'
    },
    statusBox: {
      background: '#f7fafc',
      borderRadius: '12px',
      padding: '15px',
      marginBottom: '20px',
      display: 'flex',
      alignItems: 'center',
      gap: '12px'
    },
    actionButton: {
      width: '100%',
      padding: '15px',
      background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
      color: 'white',
      border: 'none',
      borderRadius: '12px',
      fontSize: '16px',
      fontWeight: 'bold',
      cursor: 'pointer',
      transition: 'all 0.3s ease',
      marginBottom: '10px'
    },
    backButton: {
      background: 'none',
      border: 'none',
      fontSize: '16px',
      cursor: 'pointer',
      marginBottom: '20px',
      display: 'flex',
      alignItems: 'center',
      gap: '8px',
      fontWeight: '600'
    },
    fileInput: {
      width: '100%',
      padding: '15px',
      border: '2px dashed #cbd5e0',
      borderRadius: '12px',
      cursor: 'pointer'
    },
    label: {
      display: 'block',
      marginBottom: '10px',
      fontWeight: '600',
      color: '#2d3748',
      fontSize: '14px'
    }
  };

  if (!mode) {
    return (
      <div style={styles.container}>
        <div style={styles.card}>
          <h1 style={{ ...styles.title, marginBottom: '10px' }}>📤 QuickShare</h1>
          <p style={{ ...styles.subtitle, marginBottom: '40px' }}>
            Chia sẻ file P2P với WebRTC
          </p>

          <div
            style={styles.button}
            onClick={startSendMode}
            onMouseEnter={(e) => {
              e.currentTarget.style.transform = 'translateY(-2px)';
              e.currentTarget.style.borderColor = '#667eea';
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.transform = 'translateY(0)';
              e.currentTarget.style.borderColor = 'transparent';
            }}
          >
            <span style={styles.icon}>📤</span>
            <h2 style={styles.buttonTitle}>Gửi File</h2>
            <p style={styles.buttonDesc}>Tạo SDP offer và gửi file</p>
          </div>

          <div
            style={styles.button}
            onClick={startReceiveMode}
            onMouseEnter={(e) => {
              e.currentTarget.style.transform = 'translateY(-2px)';
              e.currentTarget.style.borderColor = '#48bb78';
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.transform = 'translateY(0)';
              e.currentTarget.style.borderColor = 'transparent';
            }}
          >
            <span style={styles.icon}>📥</span>
            <h2 style={styles.buttonTitle}>Nhận File</h2>
            <p style={styles.buttonDesc}>Nhập SDP offer để nhận file</p>
          </div>
        </div>
      </div>
    );
  }

  if (mode === 'send') {
    return (
      <div style={styles.container}>
        <div style={styles.card}>
          <button
            style={{ ...styles.backButton, color: '#667eea' }}
            onClick={() => { setMode(null); setIsConnected(false); }}
          >
            ← Quay lại
          </button>

          <div style={{ textAlign: 'center', marginBottom: '30px' }}>
            <span style={{ fontSize: '64px' }}>📤</span>
            <h2 style={{ fontSize: '28px', fontWeight: 'bold', margin: '15px 0', color: '#1a202c' }}>
              Gửi File
            </h2>
          </div>

          <div style={styles.statusBox}>
            <span style={{ fontSize: '24px' }}>
              {isConnected ? '✓' : '📡'}
            </span>
            <span style={{ fontSize: '14px', color: isConnected ? '#38a169' : '#718096', fontWeight: '500' }}>
              {status}
            </span>
          </div>

          {!isConnected && (
            <>
              <label style={styles.label}>Bước 1: Copy SDP Offer này</label>
              <div style={styles.sdpBox}>{localSDP || "Đang tạo..."}</div>

              <label style={styles.label}>Bước 2: Nhận SDP Answer từ người nhận</label>
              <textarea
                value={inputPin}
                onChange={(e) => setInputPin(e.target.value)}
                placeholder="Paste SDP answer từ người nhận vào đây..."
                style={styles.textarea}
              />

              <button
                onClick={connectToReceiver}
                disabled={!inputPin}
                style={{
                  ...styles.actionButton,
                  background: inputPin ? 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)' : '#cbd5e0',
                  cursor: inputPin ? 'pointer' : 'not-allowed'
                }}
              >
                Kết nối
              </button>
            </>
          )}

          {isConnected && (
            <div>
              <label style={styles.label}>Chọn file để gửi</label>
              <input
                type="file"
                onChange={(e) => {
                  const file = e.target.files[0];
                  setSelectedFile(file);
                  sendFile(file);
                }}
                style={styles.fileInput}
              />
              {selectedFile && (
                <p style={{ marginTop: '15px', fontSize: '14px', color: '#718096' }}>
                  📎 {selectedFile.name}
                </p>
              )}
            </div>
          )}
        </div>
      </div>
    );
  }

  if (mode === 'receive') {
    return (
      <div style={{ ...styles.container, background: 'linear-gradient(135deg, #48bb78 0%, #38a169 100%)' }}>
        <div style={styles.card}>
          <button
            style={{ ...styles.backButton, color: '#48bb78' }}
            onClick={() => { setMode(null); setIsConnected(false); }}
          >
            ← Quay lại
          </button>

          <div style={{ textAlign: 'center', marginBottom: '30px' }}>
            <span style={{ fontSize: '64px' }}>📥</span>
            <h2 style={{ fontSize: '28px', fontWeight: 'bold', margin: '15px 0', color: '#1a202c' }}>
              Nhận File
            </h2>
          </div>

          <div style={styles.statusBox}>
            <span style={{ fontSize: '24px' }}>
              {isConnected ? '✓' : '📡'}
            </span>
            <span style={{ fontSize: '14px', color: isConnected ? '#38a169' : '#718096', fontWeight: '500' }}>
              {status}
            </span>
          </div>

          {!localSDP && (
            <>
              <label style={styles.label}>Bước 1: Nhập SDP Offer từ người gửi</label>
              <textarea
                value={inputPin}
                onChange={(e) => setInputPin(e.target.value)}
                placeholder="Paste SDP offer từ người gửi vào đây..."
                style={styles.textarea}
              />

              <button
                onClick={connectToSender}
                disabled={!inputPin}
                style={{
                  ...styles.actionButton,
                  background: inputPin ? 'linear-gradient(135deg, #48bb78 0%, #38a169 100%)' : '#cbd5e0',
                  cursor: inputPin ? 'pointer' : 'not-allowed'
                }}
              >
                Tạo Answer
              </button>
            </>
          )}

          {localSDP && !isConnected && (
            <>
              <label style={styles.label}>Bước 2: Copy SDP Answer này và gửi lại cho người gửi</label>
              <div style={styles.sdpBox}>{localSDP}</div>
              <p style={{ fontSize: '12px', color: '#718096', marginTop: '10px' }}>
                Sau khi người gửi nhập answer, kết nối sẽ được thiết lập.
              </p>
            </>
          )}

          {isConnected && (
            <div style={{ textAlign: 'center', padding: '20px' }}>
              <span style={{ fontSize: '48px' }}>⏳</span>
              <p style={{ marginTop: '15px', color: '#718096' }}>
                Chờ người gửi chọn file...
              </p>
            </div>
          )}
        </div>
      </div>
    );
  }
}