# Guía de Deploy — Movify Backend

Deploy del backend Spring Boot en la VPS usando Apache2 como proxy reverso.

- **Frontend:** `http://movify.ds2.eleueleo.com/`
- **Backend:** `http://movify.ds2.eleueleo.com/api`
- **Puerto Spring Boot:** `8080`
- **Ruta en VPS:** `/var/www/ds2/movify-api`

---

## Requisitos previos en la VPS

### 1. Instalar Java 17

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk

# Verificar
java -version
# Esperado: openjdk version "17.x.x"
```

### 2. Instalar Maven

```bash
sudo apt install -y maven

# Verificar
mvn -version
# Esperado: Apache Maven 3.x.x
```

### 3. Verificar PostgreSQL

Asegúrate de que la base de datos y el usuario existen:

```bash
sudo -u postgres psql
```

Dentro de psql:

```sql
CREATE DATABASE movify_db;
CREATE USER dev_movify WITH PASSWORD 'Movify2026*';
GRANT ALL PRIVILEGES ON DATABASE movify_db TO dev_movify;
\q
```

Si ya existen, solo verifica:

```bash
sudo -u postgres psql -c "\l" | grep movify_db
sudo -u postgres psql -c "\du" | grep dev_movify
```

---

## Paso 1 — Crear el directorio del proyecto

```bash
sudo mkdir -p /var/www/ds2/movify-api
sudo chown $USER:$USER /var/www/ds2/movify-api
```

---

## Paso 2 — Crear el servicio systemd

```bash
sudo nano /etc/systemd/system/movify.service
```

Pega el siguiente contenido (reemplaza `TU_USUARIO` por tu usuario SSH real):

```ini
[Unit]
Description=Movify Backend Spring Boot
After=network.target postgresql.service

[Service]
Type=simple
User=TU_USUARIO
WorkingDirectory=/var/www/ds2/movify-api
ExecStart=/usr/bin/java -jar /var/www/ds2/movify-api/app.jar
Restart=on-failure
RestartSec=10
StandardOutput=append:/var/log/movify/app.log
StandardError=append:/var/log/movify/app.log

[Install]
WantedBy=multi-user.target
```

Luego:

```bash
# Crear carpeta de logs
sudo mkdir -p /var/log/movify
sudo chown TU_USUARIO:TU_USUARIO /var/log/movify

# Registrar el servicio
sudo systemctl daemon-reload
sudo systemctl enable movify
```

---

## Paso 3 — Permisos sudo para systemctl sin contraseña

El workflow de GitHub Actions ejecuta `sudo systemctl stop/start movify` de forma automática, por lo que necesita hacerlo sin que pida contraseña:

```bash
sudo visudo
```

Agrega esta línea al final (reemplaza `TU_USUARIO`):

```
TU_USUARIO ALL=(ALL) NOPASSWD: /bin/systemctl start movify, /bin/systemctl stop movify, /bin/systemctl status movify
```

---

## Paso 4 — Configurar Apache2

### Activar módulos necesarios

```bash
sudo a2enmod proxy
sudo a2enmod proxy_http
sudo a2enmod headers
sudo a2enmod rewrite
sudo systemctl restart apache2
```

### Editar el VirtualHost

```bash
sudo nano /etc/apache2/sites-available/movify.ds2.eleueleo.com.conf
```

Reemplaza el contenido con la siguiente configuración:

```apache
<VirtualHost *:80>
    ServerName   movify.ds2.eleueleo.com
    ServerAdmin  admin@ds2.local

    DocumentRoot /var/www/ds2/movify/movify-frontend/

    <Directory /var/www/ds2/movify/movify-frontend>
        Options -Indexes +FollowSymLinks
        AllowOverride All
        Require all granted
    </Directory>

    RewriteEngine On

    # SPA fallback para Angular Router (no aplica a /api)
    RewriteCond %{REQUEST_URI} !^/api
    RewriteCond %{DOCUMENT_ROOT}%{REQUEST_URI} !-f
    RewriteCond %{DOCUMENT_ROOT}%{REQUEST_URI} !-d
    RewriteRule ^ /index.html [L]

    # Backend Spring Boot
    ProxyPreserveHost On

    ProxyPass        /api/ http://localhost:8080/api/
    ProxyPassReverse /api/ http://localhost:8080/api/

    ErrorLog  ${APACHE_LOG_DIR}/movify_error.log
    CustomLog ${APACHE_LOG_DIR}/movify_access.log combined
</VirtualHost>
```

### Activar el sitio y recargar Apache

```bash
# Activar el sitio si no está activado ya
sudo a2ensite movify.ds2.eleueleo.com.conf

# Verificar que la configuración no tiene errores
sudo apache2ctl configtest
# Esperado: Syntax OK

# Recargar Apache
sudo systemctl reload apache2
```

---

## Paso 5 — Configurar Secrets en GitHub

En el repositorio de GitHub ve a **Settings → Secrets and variables → Actions** y crea los siguientes secrets:

| Secret | Descripción | Ejemplo |
|--------|-------------|---------|
| `VPS_HOST` | IP o dominio de la VPS | `123.45.67.89` |
| `VPS_USER` | Usuario SSH | `ubuntu` |
| `VPS_SSH_KEY` | Contenido de la clave privada SSH | `-----BEGIN OPENSSH PRIVATE KEY-----...` |
| `VPS_PORT` | Puerto SSH | `22` |
| `GH_PAT` | Personal Access Token de GitHub | `ghp_xxxxxxxxxxxx` |

### Cómo generar el GH_PAT

1. GitHub → **Settings** → **Developer settings**
2. **Personal access tokens** → **Tokens (classic)**
3. **Generate new token (classic)**
4. Marca el permiso `repo`
5. Copia el token generado y guárdalo como secret `GH_PAT`

### Cómo obtener la clave SSH privada

```bash
# En tu máquina local (o en la VPS si ya tienes un par de claves)
cat ~/.ssh/id_rsa
```

Copia todo el contenido (incluyendo `-----BEGIN OPENSSH PRIVATE KEY-----` y `-----END OPENSSH PRIVATE KEY-----`) y pégalo como secret `VPS_SSH_KEY`.

> Si no tienes par de claves, genera uno:
> ```bash
> ssh-keygen -t rsa -b 4096 -C "deploy-movify"
> # Agrega la clave pública a la VPS:
> ssh-copy-id -i ~/.ssh/id_rsa.pub TU_USUARIO@IP_VPS
> ```

---

## Paso 6 — Primer deploy manual

Antes de que el workflow automático funcione, haz el primer clone manualmente en la VPS:

```bash
cd /var/www/ds2

git clone https://wilsonsaa03:TU_PAT@github.com/wilsonsaa03/movify-backend.git movify-api

cd movify-api

mvn clean package -DskipTests

cp target/movify-backend-*.jar app.jar

sudo systemctl start movify

# Verificar que arrancó correctamente
sudo systemctl status movify
```

### Verificar que Spring Boot responde

```bash
# Desde la VPS
curl http://localhost:8080/api/auth/login

# Desde afuera (tu máquina)
curl http://movify.ds2.eleueleo.com/api/auth/login
```

Ambos deben devolver la misma respuesta (un `400` o `405` es correcto — confirma que la app está corriendo).

---

## Paso 7 — Activar el deploy automático

Haz push a la rama `main` para disparar el workflow de GitHub Actions:

```bash
git add .
git commit -m "fix: remove redundant @CrossOrigin, fix logging levels for production"
git push origin main
```

El workflow se puede monitorear en **GitHub → Actions**.

---

## Estructura del workflow (`.github/workflows/main.yml`)

El workflow hace lo siguiente al hacer push a `main`:

1. Se conecta a la VPS por SSH
2. Clona el repositorio si no existe, o actualiza el código con `git reset --hard`
3. Para el servicio: `sudo systemctl stop movify`
4. Compila el proyecto: `mvn clean package -DskipTests`
5. Copia el JAR generado a `app.jar`
6. Inicia el servicio: `sudo systemctl start movify`
7. Verifica el estado del servicio

---

## Verificación final

| Verificación | Comando |
|---|---|
| Estado del servicio | `sudo systemctl status movify` |
| Logs en tiempo real | `tail -f /var/log/movify/app.log` |
| Puerto escuchando | `ss -tlnp \| grep 8080` |
| Proxy Apache funcionando | `curl http://movify.ds2.eleueleo.com/api/auth/login` |

---

## Solución de problemas comunes

### El servicio no arranca
```bash
# Ver los últimos logs
tail -100 /var/log/movify/app.log

# O ver el journal de systemd
sudo journalctl -u movify -n 50 --no-pager
```

### Error 502 Bad Gateway en Apache
Significa que Apache no puede conectarse a Spring Boot. Verifica:
```bash
# ¿Está corriendo el servicio?
sudo systemctl status movify

# ¿Está escuchando en el puerto 8080?
ss -tlnp | grep 8080
```

### Error de conexión a PostgreSQL al arrancar
```bash
# Verificar que PostgreSQL está corriendo
sudo systemctl status postgresql

# Verificar credenciales
sudo -u postgres psql -c "\du" | grep dev_movify
```

### Maven no encontrado en el deploy
```bash
# Verificar que Maven está en el PATH del usuario SSH
which mvn
mvn -version
```
