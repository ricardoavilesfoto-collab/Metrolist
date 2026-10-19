# Pasar tu historial de Spotify a Metrolist Plus

Convierte el **historial de reproducción extendido** de Spotify (toda la vida de tu cuenta) en un archivo
`historial_spotify.db` que Metrolist Plus importa en **Estadísticas Plus** (ícono de estadísticas arriba en Inicio).

- Solo necesita Python (no usa `.venv` ni librerías extra).
- El archivo **no** guarda tu IP, tu país ni las sesiones de incógnito.
- Los JSON de Spotify y el `.db` nunca se suben a GitHub (están en `.git/info/exclude`).

## Pasos

1. Descarga de Spotify el ZIP del **historial extendido** ("Extended streaming history") y descomprímelo (o deja el ZIP).
2. En la PC, desde la carpeta del repo:

   ```
   set PYTHONIOENCODING=utf-8
   python herramientas\historial-spotify\preparar_historial.py "Spotify Extended Streaming History"
   ```

   Al final imprime cuántas reproducciones, canciones, artistas y horas encontró. El archivo queda junto a la
   carpeta: `D:\Metrolist\historial_spotify.db` (~26 MB).
3. Pasa `historial_spotify.db` al celular (Google Drive, cable USB o enviándotelo por WhatsApp/Telegram).
4. En Metrolist Plus: **Estadísticas → ⋮ → Importar historial de Spotify** y elige el archivo. Tarda menos de un minuto.

Puedes volver a importar cuando quieras: el historial anterior se reemplaza (no se duplica) y la app conserva las
canciones que ya había encontrado en YouTube.

## Qué pasa con los "datos de la cuenta" de Spotify

El otro paquete ("Datos de la cuenta") trae el historial **solo del último año** y sin los códigos de las
canciones. El historial extendido ya lo incluye todo, así que el script **ignora** esos archivos
(`StreamingHistory_music_*.json`) para no contar dos veces. De ese paquete sirven las **playlists** y la
**biblioteca**: esas se pasan con *Transferir música* (menú de tu foto de perfil) o con
`herramientas/spotify-a-ytmusic`.

## Cómo cuenta

- **Horas**: suma todo lo que sonó, incluso lo que saltaste.
- **Reproducciones**: solo cuentan las de 30 segundos o más (igual que el historial de Metrolist).
- **Canciones repetidas**: Spotify a veces repite el mismo registro en dos archivos. El script los quita (en este
  historial eran 799).
- Las horas se ajustan a la zona horaria del celular al importar, con el horario de verano que tenía México en cada fecha.
