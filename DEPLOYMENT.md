# Gallery Backend — Production Deployment Guide

This guide details step-by-step production deployment on a Linux VPS (Ubuntu 22.04 / Debian 12) with PostgreSQL, systemd, Caddy reverse proxy (automatic HTTPS/TLS), and admin seeding.

---

## Architecture Overview

```
[Phone / Web Dashboard]
        │ HTTPS / WSS
        ▼
   [Caddy Proxy]  (:80 / :443)
        │
        │ HTTP / WS (localhost:8080)
        ▼
 [Gallery Backend]  (systemd service)
        │
        ▼
   [PostgreSQL]     (localhost:5432)
```

---

## Step 1: Server Provisioning & Prerequisites

1. Provision a Linux VPS (1 CPU, 2 GB RAM minimum recommended).
2. Install required system packages:
   ```bash
   sudo apt update && sudo apt install -y openjdk-21-jre-headless postgresql postgresql-contrib curl git
   ```

---

## Step 2: Configure PostgreSQL Database

1. Connect to PostgreSQL:
   ```bash
   sudo -u postgres psql
   ```
2. Create database, user, and schema permissions:
   ```sql
   CREATE DATABASE gallery;
   CREATE USER gallery_app WITH PASSWORD 'YOUR_SECURE_DB_PASSWORD';
   GRANT ALL PRIVILEGES ON DATABASE gallery TO gallery_app;
   \c gallery
   GRANT ALL ON SCHEMA public TO gallery_app;
   ALTER SCHEMA public OWNER TO gallery_app;
   \q
   ```

---

## Step 3: Application Build & Configuration

1. Clone or sync the project repository to `/opt/gallery-backend`:
   ```bash
   sudo mkdir -p /opt/gallery-backend
   sudo chown -R $USER:$USER /opt/gallery-backend
   cd /opt/gallery-backend
   ```

2. Build the production Fat JAR:
   ```bash
   ./gradlew shadowJar
   ```
   The built JAR is located at `build/libs/gallery-backend-0.1.0-all.jar`.

3. Create the production environment configuration file `/opt/gallery-backend/.env`:
   ```bash
   cat <<'EOF' > /opt/gallery-backend/.env
   DB_URL=jdbc:postgresql://localhost:5432/gallery
   DB_USER=gallery_app
   DB_PASSWORD=YOUR_SECURE_DB_PASSWORD
   
   ADMIN_USER=admin
   ADMIN_PASSWORD=YOUR_SECURE_ADMIN_PASSWORD
   
   JWT_SECRET=YOUR_64_CHAR_RANDOM_SECRET_KEY
   DASHBOARD_ORIGIN=https://dashboard.yourdomain.com
   PORT=8080
   STAGING_DIR=/var/lib/gallery-backend/staging
   EOF
   
   sudo chmod 600 /opt/gallery-backend/.env
   ```

4. Create the staging directory:
   ```bash
   sudo mkdir -p /var/lib/gallery-backend/staging
   ```

---

## Step 4: Seed Admin User

Seed the initial admin account using the CLI tool:

```bash
./gradlew seedAdmin --args="admin YOUR_SECURE_ADMIN_PASSWORD"
```

---

## Step 5: Configure systemd Service

1. Create `/etc/systemd/system/gallery-backend.service`:
   ```ini
   [Unit]
   Description=Gallery Backend Ktor Service
   After=network.target postgresql.service
   Wants=postgresql.service

   [Service]
   Type=simple
   User=www-data
   Group=www-data
   WorkingDirectory=/opt/gallery-backend
   EnvironmentFile=/opt/gallery-backend/.env
   ExecStart=/usr/bin/java -jar /opt/gallery-backend/build/libs/gallery-backend-0.1.0-all.jar
   Restart=always
   RestartSec=5s
   LimitNOFILE=65536

   [Install]
   WantedBy=multi-user.target
   ```

2. Set permissions and start service:
   ```bash
   sudo chown -R www-data:www-data /opt/gallery-backend /var/lib/gallery-backend
   sudo systemctl daemon-reload
   sudo systemctl enable gallery-backend
   sudo systemctl start gallery-backend
   ```

3. Check service status & logs:
   ```bash
   sudo systemctl status gallery-backend
   sudo journalctl -u gallery-backend -f
   ```

---

## Step 6: Configure Caddy Reverse Proxy (HTTPS/WSS)

1. Install Caddy:
   ```bash
   sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https
   curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
   curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
   sudo apt update && sudo apt install caddy
   ```

2. Configure `/etc/caddy/Caddyfile`:
   ```caddy
   api.yourdomain.com {
       reverse_proxy localhost:8080
   }
   ```

3. Reload Caddy:
   ```bash
   sudo systemctl reload caddy
   ```
   Caddy automatically provisions Let's Encrypt TLS certificates and proxies HTTPS / WebSocket requests to the backend.

---

## Step 7: Post-Deployment Verification

1. **Health Check**:
   ```bash
   curl -i https://api.yourdomain.com/health
   ```
2. **Admin Login**:
   ```bash
   curl -i -X POST https://api.yourdomain.com/api/auth/login \
     -H "Content-Type: application/json" \
     -d '{"username":"admin", "password":"YOUR_SECURE_ADMIN_PASSWORD"}'
   ```
