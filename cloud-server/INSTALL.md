# GAC Cloud Server Installation

## Quick Start (auf deinem Server)

### 1. Dateien kopieren
```bash
scp -r cloud-server/* root@gaccloud.node2.serves.deldown.de:/opt/gac-cloud/
```

### 2. Auf dem Server installieren
```bash
ssh root@gaccloud.node2.serves.deldown.de

# Verzeichnis erstellen
mkdir -p /opt/gac-cloud
cd /opt/gac-cloud

# Python installieren (falls nicht vorhanden)
apt update && apt install -y python3 python3-pip python3-venv

# Dependencies installieren
pip3 install -r requirements.txt

# Starten
python3 main.py
```

### 3. Als Service einrichten (automatischer Start)
```bash
# Service-Datei kopieren
cp gac-cloud.service /etc/systemd/system/

# Service aktivieren
systemctl daemon-reload
systemctl enable gac-cloud
systemctl start gac-cloud

# Status prüfen
systemctl status gac-cloud
```

### 4. Mit Docker (Alternative)
```bash
cd /opt/gac-cloud
docker-compose up -d
```

## Testen

```bash
curl http://localhost:17865/api/v2/health
```

Sollte zurückgeben:
```json
{
  "status": "healthy",
  "version": "2.0.0",
  "total_players": 0,
  "total_servers": 0,
  "data_points": 0
}
```

## Ports

- **17865** - HTTP API (Hauptport)

## Features

- **Isolation Forest** - Erkennt anomales Verhalten
- **Temporal Analysis** - Erkennt Muster über Zeit (konstante CPS, Speed, etc.)
- **Pattern Matching** - Erkennt bekannte Cheat-Signaturen
- **Cross-Server Trust** - Spieler-Reputation über mehrere Server
- **Keine Bans** - Nur subtile Mitigations

## API Endpoints

- `GET /api/v2/health` - Health Check
- `POST /api/v2/behavior/upload` - Behavior-Daten hochladen
- `GET /api/v2/player/{hash}/analysis` - Spieler-Analyse abrufen
- `POST /api/v2/violation/report` - Violation melden
- `POST /api/v2/training/label` - Training-Label setzen
