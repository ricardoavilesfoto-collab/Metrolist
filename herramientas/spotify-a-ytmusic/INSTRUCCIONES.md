# Pasar mis "Me gusta" de Spotify a YouTube Music

Script que lleva tus canciones "Me gusta" de Spotify (~8,000) a YouTube Music. Como YouTube Music acepta máximo
5,000 canciones por playlist, las reparte en dos playlists privadas de ~4,000 cada una, en el mismo orden que
Spotify (las más recientes primero):

- **Spotify Me gusta (1/2)**
- **Spotify Me gusta (2/2)**

Opcionalmente también les da "Me gusta" en YouTube Music. En Metrolist Plus aparecen solas al sincronizar la
Biblioteca.

> **Dónde vive:** la copia oficial del script está en el repo (`herramientas/spotify-a-ytmusic/`), para que
> Claude la vea también desde el celular. **Se ejecuta en la PC**, en `D:\Herramientas\spotify-a-ytmusic\`
> (ahí están el `.venv` y tus archivos privados). Claude en la PC copia el script del repo a esa carpeta antes
> de correrlo. Tus archivos (`*.csv`, `encabezados.txt`, `browser.json`, `progreso.json`) nunca se suben a GitHub.

---

## Lo que tienes que hacer tú (2 pasos)

### Paso 1 — Descargar tus "Me gusta" de Spotify

1. Entra a <https://exportify.net> y toca **Get Started**. Inicia sesión con Spotify y acepta.
2. En la lista busca **Liked Songs** y toca **Export**. Se descarga un archivo `.csv`.
3. Mueve ese archivo a esta carpeta (`D:\Herramientas\spotify-a-ytmusic\`) y renómbralo **`Liked_Songs.csv`**.

### Paso 2 — Conectar tu cuenta de YouTube Music

1. En **Chrome**, abre <https://music.youtube.com> con tu sesión iniciada.
2. Presiona **F12** → pestaña **Network** (Red). En el cuadro de filtro escribe **`browse`**.
3. En YouTube Music haz clic en **Biblioteca** para que aparezca una línea llamada `browse`.
4. Clic derecho sobre esa línea → **Copy** → **Copy request headers**.
5. Abre el **Bloc de notas**, pega (Ctrl+V) y guárdalo como **`encabezados.txt`** en esta carpeta.

> ⚠️ Esos encabezados son tu sesión de YouTube (como una llave de tu cuenta). No los pegues en ningún chat
> ni los compartas. El script borra `encabezados.txt` en cuanto se conecta y guarda la conexión en
> `browser.json`, que tampoco debes compartir.

Cuando tengas los dos archivos, abre Claude Code en `D:\Metrolist` y dile: **"listo, corre el script de Spotify"**.

---

## Lo que hace Claude (en este orden)

| # | Comando | Qué hace |
|---|---------|----------|
| 1 | `configurar --archivo encabezados.txt` | Conecta tu cuenta y confirma que funciona. |
| 2 | `buscar Liked_Songs.csv --limite 20` | Prueba con 20 canciones para revisar que encuentre las correctas. |
| 3 | `buscar Liked_Songs.csv` | Busca las ~8,000. Tarda **varias horas**; si se interrumpe, continúa donde se quedó. |
| 4 | `reporte` | Crea `reporte.csv` (se abre con Excel) con las canciones **no encontradas** o **dudosas**. |
| 5 | `subir` (o `subir --me-gusta`) | Crea las dos playlists privadas y les agrega las canciones (y opcionalmente les da "Me gusta"). |

Todos los comandos se corren así desde esta carpeta:

```powershell
cd D:\Herramientas\spotify-a-ytmusic
.\.venv\Scripts\python.exe spotify_a_ytmusic.py <comando>
```

---

## Cómo decide si encontró la canción correcta

Busca en YouTube Music por **título + primer artista** (primero en canciones, luego en videos) y le pone un
puntaje a cada resultado:

- **Título** (55 %): ignora acentos y textos como "Remastered 2011", "(feat. …)", "(Live)".
- **Artista** (35 %): si el artista no coincide, el puntaje se castiga fuerte (así descarta covers).
- **Duración** (10 %): suma si dura lo mismo (±3 s).

| Puntaje | Resultado |
|---------|-----------|
| 0.75 o más | ✓ Encontrada (segura) |
| 0.55 – 0.75 | ? Dudosa: se agrega, pero aparece en el reporte para revisarla |
| menos de 0.55 | ✗ No encontrada: no se agrega, aparece en el reporte |

Lo normal es encontrar entre el 90 y 95 %.

---

## Archivos de la carpeta

| Archivo | Qué es | ¿Se puede borrar? |
|---------|--------|-------------------|
| `spotify_a_ytmusic.py` | El script | No |
| `INSTRUCCIONES.md` | Este documento | No |
| `.venv\` | Python con la librería `ytmusicapi` 1.12.3 instalada | No (se puede recrear) |
| `Liked_Songs.csv` | Tus "Me gusta" exportados de Spotify | Sí, al terminar |
| `encabezados.txt` | Tu sesión de YouTube (temporal) | Se borra solo |
| `browser.json` | Conexión guardada con tu cuenta | Sí, al terminar (por seguridad) |
| `progreso.json` | Avance: qué canción encontró y qué ya subió | Sí, al terminar |
| `reporte.csv` | No encontradas y dudosas | Sí |

---

## Si algo falla

- **"Primero conecta tu cuenta"** → falta el paso 2.
- **Errores repetidos al buscar o 401/403** → la sesión caducó: repite el paso 2 y corre `configurar` otra vez.
  El avance no se pierde.
- **YouTube bloquea temporalmente por muchas búsquedas** → el script espera y reintenta solo; si persiste,
  pausa con Ctrl+C y continúa más tarde con el mismo comando.
- **Rehacer todo desde cero** → borra `progreso.json`. Si ya se crearon playlists, bórralas antes en YouTube Music.

## Recrear el entorno (solo si borraste `.venv`)

```powershell
cd D:\Herramientas\spotify-a-ytmusic
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install ytmusicapi
```

---

## Siguiente paso pendiente (otro proyecto)

Importar tu **historial y estadísticas de Spotify** (más escuchados, artistas favoritos, minutos) a Metrolist Plus.
Para eso primero pide tus datos en spotify.com → Cuenta → **Privacidad** → *Descargar tus datos*
("Datos de la cuenta" tarda ~5 días; "Historial de reproducción ampliado" hasta 30 días).
