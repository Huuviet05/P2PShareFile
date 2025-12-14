# 🚀 DEPLOY RELAY SERVER KHÔNG DÙNG DOCKER

## 📋 TẠI SAO KHÔNG DÙNG DOCKER?

-  ⚡ **Build nhanh hơn** (không cần build Docker image)
-  💾 **Ít tốn tài nguyên** (không có overhead của container)
-  🛠️ **Dễ debug** (logs trực tiếp, không qua Docker)
-  📦 **Đơn giản hơn** (chỉ cần Java runtime)

---

## ✅ CÁCH 1: RENDER (Native Java) - KHUYẾN NGHỊ

### Bước 1: Chuẩn bị repo

```bash
# Đảm bảo code đã push lên GitHub
git add .
git commit -m "Add relay server"
git push origin main
```

### Bước 2: Tạo Web Service trên Render

1. Vào https://render.com → **Sign up** (miễn phí)
2. Click **"New +"** → **"Web Service"**
3. Connect GitHub repository
4. Chọn repo `P2PShareFile`

### Bước 3: Cấu hình

```yaml
Name: p2p-relay-server
Region: Singapore (hoặc gần bạn)
Branch: main
Runtime: Java
Build Command: mvn clean package -DskipTests
Start Command: java -cp target/classes:target/P2PShareFile-1.0-SNAPSHOT.jar org.example.p2psharefile.relay.StandaloneRelayServer
Instance Type: Free
```

### Bước 4: Environment Variables

Thêm trong Render dashboard:

```bash
PORT=10000
STORAGE_DIR=/tmp/relay-storage
FILE_EXPIRY_HOURS=24
MAX_FILE_SIZE_MB=100
ENABLE_CORS=true
JAVA_TOOL_OPTIONS=-Xmx512m -Xms256m
```

### Bước 5: Deploy

-  Click **"Create Web Service"**
-  Chờ 5-10 phút build + deploy
-  Lấy URL: `https://p2p-relay-server.onrender.com`

### Bước 6: Config Client

```bash
# Windows
set RELAY_SERVER_URL=https://p2p-relay-server.onrender.com
set START_RELAY_SERVER=false

# Linux/Mac
export RELAY_SERVER_URL=https://p2p-relay-server.onrender.com
export START_RELAY_SERVER=false
```

---

## ✅ CÁCH 2: RAILWAY.APP

### Ưu điểm:

-  ✅ Tự động detect Java app
-  ✅ Free $5 credit/month
-  ✅ Không sleep (luôn online)
-  ✅ Faster deployment

### Deploy:

```bash
# 1. Install Railway CLI
npm i -g @railway/cli

# 2. Login
railway login

# 3. Init project
railway init

# 4. Deploy
railway up

# 5. Lấy URL
railway domain
```

### Config trong Railway Dashboard:

```bash
PORT=$PORT  # Railway tự động set
STORAGE_DIR=/tmp/relay-storage
FILE_EXPIRY_HOURS=24
MAX_FILE_SIZE_MB=100
START_COMMAND=java -cp target/classes:target/*.jar org.example.p2psharefile.relay.StandaloneRelayServer
```

---

## ✅ CÁCH 3: HEROKU

### Bước 1: Tạo Procfile

```bash
echo "web: java -cp target/classes:target/P2PShareFile-1.0-SNAPSHOT.jar org.example.p2psharefile.relay.StandaloneRelayServer" > Procfile
```

### Bước 2: Tạo system.properties

```bash
echo "java.runtime.version=21" > system.properties
```

### Bước 3: Deploy

```bash
# Install Heroku CLI
# https://devcenter.heroku.com/articles/heroku-cli

# Login
heroku login

# Create app
heroku create p2p-relay-server

# Set config
heroku config:set PORT=$PORT
heroku config:set STORAGE_DIR=/tmp/relay-storage
heroku config:set FILE_EXPIRY_HOURS=24

# Deploy
git push heroku main

# Get URL
heroku open
```

---

## ✅ CÁCH 4: VPS (SELF-HOSTED)

### Yêu cầu:

-  Ubuntu 20.04+ hoặc CentOS 8+
-  Java 21+
-  512MB RAM minimum
-  1GB disk space

### Bước 1: Cài đặt Java

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install openjdk-21-jdk maven -y

# CentOS/RHEL
sudo yum install java-21-openjdk maven -y
```

### Bước 2: Clone repo

```bash
cd /opt
git clone https://github.com/your-username/P2PShareFile.git
cd P2PShareFile
```

### Bước 3: Build

```bash
mvn clean package -DskipTests
```

### Bước 4: Tạo service systemd

```bash
sudo nano /etc/systemd/system/relay-server.service
```

Nội dung:

```ini
[Unit]
Description=P2P Relay Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/opt/P2PShareFile
Environment="PORT=8080"
Environment="STORAGE_DIR=/opt/relay-storage"
Environment="FILE_EXPIRY_HOURS=24"
ExecStart=/usr/bin/java -cp target/classes:target/P2PShareFile-1.0-SNAPSHOT.jar org.example.p2psharefile.relay.StandaloneRelayServer
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

### Bước 5: Start service

```bash
# Reload systemd
sudo systemctl daemon-reload

# Enable auto-start
sudo systemctl enable relay-server

# Start service
sudo systemctl start relay-server

# Check status
sudo systemctl status relay-server

# View logs
sudo journalctl -u relay-server -f
```

### Bước 6: Cấu hình Firewall

```bash
# Ubuntu (ufw)
sudo ufw allow 8080/tcp

# CentOS (firewalld)
sudo firewall-cmd --permanent --add-port=8080/tcp
sudo firewall-cmd --reload
```

### Bước 7: Setup Nginx reverse proxy (Optional)

```bash
sudo apt install nginx -y
sudo nano /etc/nginx/sites-available/relay
```

Nội dung:

```nginx
server {
    listen 80;
    server_name relay.yourdomain.com;

    location / {
        proxy_pass http://localhost:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/relay /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl restart nginx
```

### Bước 8: SSL với Let's Encrypt (Optional)

```bash
sudo apt install certbot python3-certbot-nginx -y
sudo certbot --nginx -d relay.yourdomain.com
```

---

## ✅ CÁCH 5: FLY.IO

### Tạo fly.toml

```toml
app = "p2p-relay-server"
primary_region = "sin"

[build]
  builder = "paketobuildpacks/builder:base"
  buildpacks = ["gcr.io/paketo-buildpacks/java"]

[env]
  PORT = "8080"
  STORAGE_DIR = "/data/relay-storage"
  FILE_EXPIRY_HOURS = "24"

[[services]]
  http_checks = []
  internal_port = 8080
  processes = ["app"]
  protocol = "tcp"
  script_checks = []

  [[services.ports]]
    force_https = true
    handlers = ["http"]
    port = 80

  [[services.ports]]
    handlers = ["tls", "http"]
    port = 443

[mounts]
  source = "relay_storage"
  destination = "/data/relay-storage"
```

### Deploy:

```bash
# Install Fly CLI
curl -L https://fly.io/install.sh | sh

# Login
fly auth login

# Deploy
fly launch
fly deploy

# Get URL
fly status
```

---

## 📊 SO SÁNH CÁC CÁCH

| Platform    | Giá    | Setup      | Performance | Uptime     | Khuyến nghị             |
| ----------- | ------ | ---------- | ----------- | ---------- | ----------------------- |
| **Render**  | Free   | ⭐⭐⭐⭐⭐ | ⭐⭐⭐      | ⭐⭐⭐     | Tốt nhất cho beginner   |
| **Railway** | $5/mo  | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐    | ⭐⭐⭐⭐⭐ | Tốt nhất cho production |
| **Heroku**  | $7/mo  | ⭐⭐⭐⭐   | ⭐⭐⭐      | ⭐⭐⭐⭐   | Ổn định, cũ             |
| **VPS**     | $5+/mo | ⭐⭐       | ⭐⭐⭐⭐⭐  | ⭐⭐⭐⭐   | Tốt nếu biết Linux      |
| **Fly.io**  | Free   | ⭐⭐⭐⭐   | ⭐⭐⭐⭐    | ⭐⭐⭐⭐   | Edge computing          |

---

## 🧪 TEST SAU KHI DEPLOY

```bash
# Test health check
curl https://your-server.onrender.com/api/relay/status/health

# Test peer list
curl https://your-server.onrender.com/api/peers/list

# Hoặc dùng script
./test-relay-server.bat https://your-server.onrender.com
```

---

## ⚠️ LƯU Ý

### Render Free Plan:

-  ⚠️ **Sleep sau 15 phút** không dùng
-  ⚠️ **Cold start ~30s** khi wake up
-  💡 **Giải pháp:** Dùng cron job ping mỗi 10 phút:
   ```bash
   # Crontab
   */10 * * * * curl https://your-server.onrender.com/api/relay/status/health
   ```

### Railway Free:

-  ✅ Không sleep
-  ⚠️ Giới hạn $5/month
-  💡 Đủ cho ~500GB bandwidth

### VPS:

-  ✅ Full control
-  ✅ Không giới hạn
-  ⚠️ Phải tự quản lý bảo mật, backup

---

## 🎯 KHUYẾN NGHỊ

**Cho beginner:**
→ **Render** (miễn phí, dễ nhất)

**Cho production nhỏ:**
→ **Railway** (no sleep, $5/mo)

**Cho production lớn:**
→ **VPS** (full control, scalable)

**Cho edge computing:**
→ **Fly.io** (nhanh, gần user)

---

## 📚 TÀI LIỆU THAM KHẢO

-  [Render Java Deployment](https://render.com/docs/deploy-java)
-  [Railway Java Guide](https://docs.railway.app/languages/java)
-  [Heroku Java Support](https://devcenter.heroku.com/articles/java-support)
-  [Fly.io Java Apps](https://fly.io/docs/languages-and-frameworks/java/)

---

## ❓ TROUBLESHOOTING

### Lỗi: "Build failed"

```bash
# Kiểm tra Java version
java -version  # Cần Java 21+

# Build local trước
mvn clean package
```

### Lỗi: "Out of memory"

```bash
# Tăng heap size
JAVA_TOOL_OPTIONS=-Xmx512m -Xms256m
```

### Lỗi: "Port already in use"

```bash
# Đổi port khác
PORT=9090
```

---

🎉 **Chúc bạn deploy thành công!**
