# GAC Auto-Update System

## Fuer Entwickler / AIs

### JAR bauen und hochladen

```bash
# 1. In das GAC Verzeichnis wechseln
cd /home/matti/Nextcloud/plugins/GAC

# 2. JAR bauen
mvn package -DskipTests

# 3. Zur Cloud hochladen
curl -X POST "http://CLOUD_URL:17865/api/v2/update/upload" \
     -H "X-Admin-Key: gac-admin-secret" \
     -H "X-Plugin-Version: 1.0.X" \
     -H "Content-Type: application/octet-stream" \
     --data-binary @target/gac-1.0-SNAPSHOT.jar
```

### Oder mit dem Upload-Script

```bash
cd cloud-server
chmod +x upload_plugin.sh

# Standard (localhost)
./upload_plugin.sh ../target/gac-1.0-SNAPSHOT.jar 1.0.1

# Mit custom URL
GAC_CLOUD_URL=http://dein-server:17865 ./upload_plugin.sh ../target/gac-1.0-SNAPSHOT.jar 1.0.1
```

### Umgebungsvariablen

| Variable | Default | Beschreibung |
|----------|---------|--------------|
| GAC_CLOUD_URL | http://localhost:17865 | Cloud Server URL |
| GAC_ADMIN_KEY | gac-admin-secret | Admin Key fuer Uploads |

---

## Fuer Server-Admins

### Update installieren

```
/gac update
```

Das wars! Der Befehl:
1. Prueft ob ein Update verfuegbar ist
2. Laedt die neue JAR runter
3. Ersetzt die alte JAR
4. Zeigt Meldung dass Server neugestartet werden muss

### Nach dem Update

Server neustarten damit das neue Plugin geladen wird:
```
/stop
```

---

## API Endpoints

### Version pruefen
```
GET /api/v2/update/version

Response:
{
    "version": "1.0.1",
    "available": true,
    "size": 365341,
    "modified": "2026-02-01T21:50:00",
    "download_url": "/api/v2/update/download"
}
```

### JAR herunterladen
```
GET /api/v2/update/download

Response: Binary JAR file
```

### JAR hochladen (Admin)
```
POST /api/v2/update/upload
Headers:
  X-Admin-Key: gac-admin-secret
  X-Plugin-Version: 1.0.1
  Content-Type: application/octet-stream
Body: Binary JAR data

Response:
{
    "success": true,
    "version": "1.0.1",
    "size": 365341,
    "message": "Plugin v1.0.1 uploaded successfully"
}
```

---

## Workflow fuer Claude/AI

1. Code aendern
2. `mvn package -DskipTests`
3. Upload zur Cloud:
   ```bash
   curl -X POST "http://localhost:17865/api/v2/update/upload" \
        -H "X-Admin-Key: gac-admin-secret" \
        -H "X-Plugin-Version: 1.0.X" \
        --data-binary @target/gac-1.0-SNAPSHOT.jar
   ```
4. User sagen: "Update mit `/gac update` und restart"

NICHT mehr manuell kopieren!
