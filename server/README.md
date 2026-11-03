# Tisma Server en tismaservidores.net

`TismaServer.exe` recibe los escaneos de la app Tisma Scanner, los procesa (imágenes → COLMAP →
nube densa → postprocesado) y devuelve la pieza en STL (mm) con un informe de calidad.

## 1. Requisitos del servidor (Windows 10/11 o Windows Server 2019+)

| Componente | Para qué | Dónde |
|---|---|---|
| **GPU NVIDIA** + driver reciente | COLMAP y la nube densa van 10-50× más rápido | — |
| **COLMAP** (versión con CUDA) | fotogrametría | https://github.com/colmap/colmap/releases → `COLMAP-x.y-windows-cuda.zip` |
| **OpenMVS** (opcional, recomendado) | nube densa y malla texturizada | https://github.com/cdcseacave/openMVS/releases |
| Disco | ~3-5 GB por escaneo mientras se procesa | SSD |
| RAM | 16 GB mínimo, 32 GB recomendado | — |

## 2. Instalar

1. Descomprime COLMAP en `C:\COLMAP` y OpenMVS en `C:\OpenMVS`.
2. Crea `C:\TismaServer` y copia ahí `TismaServer.exe`.
3. Comprueba que todo funciona en esa máquina:
   ```
   C:\TismaServer> TismaServer.exe --selftest
   ```
   Debe terminar con `AUTOPRUEBA OK`.
4. Arráncalo una vez: `TismaServer.exe`. Crea `tisma_server.json` y muestra la **clave de API**:
   cópiala (va en la app: ⚙ → Nube). Ciérralo (Ctrl+C).
5. Edita `tisma_server.json` (ver `tisma_server.example.json`): rutas de `colmap` y
   `openmvs_dir`, y el certificado HTTPS (paso 3).

## 3. HTTPS para tismaservidores.net

La app sólo envía escaneos por HTTPS. Dos opciones:

**A. Certificado en el propio servidor** (lo más simple):
1. Apunta el DNS `tismaservidores.net` (registro A) a la IP pública del servidor.
2. Saca un certificado gratuito de Let's Encrypt con **win-acme** (https://www.win-acme.com/):
   elige "certificado manual", dominio `tismaservidores.net`, y exportación **PEM** a
   `C:\TismaServer\certs`. win-acme lo renueva solo.
3. Pon las rutas en `tls_cert` / `tls_key` y usa `"port": 443` (o 8443 y abre ese puerto).

**B. Detrás de IIS o Caddy** (si el servidor ya sirve otras webs): deja `tls_cert` vacío,
`"host": "127.0.0.1"`, `"port": 8443`, y haz que el proxy reenvíe `https://tismaservidores.net/api/`
a `http://127.0.0.1:8443/api/`, permitiendo cuerpos de hasta 20 MB por petición.

Abre el puerto en el firewall de Windows:
```
netsh advfirewall firewall add rule name="Tisma Server" dir=in action=allow protocol=TCP localport=443
```

## 4. Dejarlo como servicio (arranca solo con Windows)

Con **NSSM** (https://nssm.cc):
```
nssm install TismaServer C:\TismaServer\TismaServer.exe
nssm set TismaServer AppDirectory C:\TismaServer
nssm set TismaServer AppStdout C:\TismaServer\servidor.log
nssm set TismaServer AppStderr C:\TismaServer\servidor.log
nssm start TismaServer
```

## 5. Comprobar desde fuera

```
curl https://tismaservidores.net/api/health
```
→ `{"ok": true, "version": "...", "queue": 0}`

## Qué hace con cada escaneo

1. Recibe el .zip en trozos de 16 MB; si se corta la conexión, la app sigue donde lo dejó.
2. Valida el zip (formato de Tisma Scanner, sin rutas peligrosas, tamaño descomprimido).
3. Lo pone en cola (uno a la vez: la GPU es el cuello de botella).
4. Proceso: HDR/apilados/ráfagas → realce de imágenes (Wallis) → pares a comparar según la
   posición INS de cada foto → puntos y emparejado (IA o SIFT) → COLMAP → descarte de fotos
   mal registradas con la INS → escala (ArUco > enfoque > INS) → nube densa → postprocesado.

### Puntos con IA (ALIKED + LightGlue), opcional

Mucho mejores que SIFT en piezas lisas o mate. Licencias que permiten uso comercial
(ALIKED BSD-3, LightGlue Apache-2.0). Para activarlo, en el Python del servidor:

    pip install torch lightglue        # con GPU NVIDIA: torch con CUDA, mucho más rápido

Si no está instalado, el log dice `aliked no disponible` y se usa SIFT de COLMAP como antes.
(No usar SuperPoint original ni SuperGlue: su licencia es sólo para investigación.)
5. Resultados descargables por la app: `pieza.stl` (mm), `pieza_informe.json`, `pieza.ply`,
   `textura.zip` (malla con color de OpenMVS), `log.txt`.
6. Borra el zip al terminar y el trabajo entero a los `retention_days` días.

## Seguridad

- Todas las llamadas (salvo `/api/health`) exigen la clave de API (`Authorization: Bearer …`),
  comparada en tiempo constante. Puedes tener varias claves en `api_keys` (una por cliente).
- Límites de tamaño por trozo, por subida y descomprimido; IDs aleatorios validados; sólo se
  pueden descargar los archivos de resultados.
- Usa siempre HTTPS: la clave viaja en cada petición.

## API

```
GET    /api/health
POST   /api/scans                     {"size": bytes, "name": "..."}  -> {"id", "chunk_size"}
PUT    /api/scans/<id>/data?offset=N  (trozo; 409 + {"received"} si hay que reanudar)
POST   /api/scans/<id>/complete
GET    /api/scans/<id>                estado, etapa, registro, resultados, informe
GET    /api/scans/<id>/files/<nombre>
DELETE /api/scans/<id>
GET    /api/scans
```
