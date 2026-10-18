"""
Pasa tus canciones "Me gusta" de Spotify a YouTube Music.

Como YouTube Music acepta como máximo 5,000 canciones por playlist, las reparte en partes iguales
(por ejemplo 8,000 → dos playlists de 4,000), en el mismo orden que Spotify (las más recientes primero).

Pasos:
  1) python spotify_a_ytmusic.py configurar
       Conecta tu cuenta de YouTube Music (una sola vez).
  2) python spotify_a_ytmusic.py buscar Liked_Songs.csv [--limite 20]
       Busca cada canción en YouTube Music. Es lo más tardado; se puede interrumpir con Ctrl+C
       y al volver a correrlo continúa donde se quedó.
  3) python spotify_a_ytmusic.py reporte
       Genera reporte.csv con las canciones no encontradas o dudosas, para revisarlas.
  4) python spotify_a_ytmusic.py subir [--me-gusta]
       Crea las playlists privadas y les agrega las canciones. Con --me-gusta además las marca
       como "Me gusta" en YouTube Music.

El CSV se descarga de https://exportify.net (botón "Export" en "Liked Songs").
"""

import argparse
import csv
import json
import math
import re
import sys
import time
import unicodedata
from difflib import SequenceMatcher
from pathlib import Path

from ytmusicapi import YTMusic, setup
from ytmusicapi.models.content.enums import LikeStatus

BASE_DIR = Path(__file__).resolve().parent
AUTH_FILE = BASE_DIR / "browser.json"
PROGRESS_FILE = BASE_DIR / "progreso.json"
REPORT_FILE = BASE_DIR / "reporte.csv"

MAX_PER_PLAYLIST = 4900
PLAYLIST_TITLE = "Spotify Me gusta"
ACCEPT_SCORE = 0.55
SURE_SCORE = 0.75
SEARCH_PAUSE_S = 0.4
BATCH_SIZE = 100

sys.stdout.reconfigure(encoding="utf-8")


# ---------- progreso ----------

def load_progress() -> dict:
    if PROGRESS_FILE.exists():
        return json.loads(PROGRESS_FILE.read_text(encoding="utf-8"))
    return {"tracks": {}, "order": [], "playlists": [], "liked": []}


def save_progress(progress: dict) -> None:
    tmp = PROGRESS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(progress, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(PROGRESS_FILE)


def get_client() -> YTMusic:
    if not AUTH_FILE.exists():
        sys.exit("Primero conecta tu cuenta:  python spotify_a_ytmusic.py configurar")
    return YTMusic(str(AUTH_FILE))


def with_retries(action, what: str):
    for attempt in range(6):
        try:
            return action()
        except Exception as error:  # noqa: BLE001 - la API no oficial lanza excepciones variadas
            wait = min(60, 5 * 2 ** attempt)
            print(f"  ! Error al {what}: {error}. Reintento en {wait}s...")
            time.sleep(wait)
    raise RuntimeError(f"No se pudo {what} después de varios intentos.")


# ---------- configurar ----------

def cmd_configurar(args) -> None:
    if args.archivo:
        headers_file = Path(args.archivo)
        if not headers_file.is_absolute():
            headers_file = BASE_DIR / headers_file
        raw = headers_file.read_text(encoding="utf-8")
    else:
        print(
            "Conectar tu cuenta de YouTube Music\n"
            "1. Abre https://music.youtube.com en Chrome con tu sesión iniciada.\n"
            "2. Presiona F12 → pestaña 'Network' (Red). En el filtro escribe: browse\n"
            "3. Haz clic en cualquier parte de YouTube Music (por ejemplo 'Biblioteca') para que aparezca una petición 'browse'.\n"
            "4. Clic derecho sobre la petición → Copy → 'Copy request headers' (Copiar encabezados de solicitud).\n"
            "5. Pega aquí abajo, presiona Enter, luego Ctrl+Z y Enter otra vez.\n"
        )
        raw = sys.stdin.read()
    if "cookie" not in raw.lower():
        sys.exit("No encontré la cookie en lo que pegaste. Repite el paso 4 con una petición 'browse'.")
    setup(filepath=str(AUTH_FILE), headers_raw=raw)
    yt = YTMusic(str(AUTH_FILE))
    playlists = yt.get_library_playlists(limit=5)
    print(f"\n✓ Cuenta conectada. Veo {len(playlists)} de tus playlists, por ejemplo: "
          f"{', '.join(p['title'] for p in playlists[:3])}")
    print("Guardé la conexión en browser.json. No compartas ese archivo: da acceso a tu cuenta.")
    if args.archivo:
        headers_file.unlink(missing_ok=True)
        print(f"Borré {headers_file.name}: ya no hace falta y contiene tu sesión.")


# ---------- buscar ----------

def read_spotify_csv(path: Path) -> list[dict]:
    with path.open(encoding="utf-8-sig", newline="") as file:
        rows = list(csv.DictReader(file))
    if not rows:
        sys.exit("El CSV está vacío.")

    def column(*names):
        lowered = {key.lower().strip(): key for key in rows[0].keys()}
        for name in names:
            if name in lowered:
                return lowered[name]
        return None

    uri_col = column("track uri", "spotify id", "uri")
    name_col = column("track name", "name", "title")
    artist_col = column("artist name(s)", "artist name", "artists", "artist")
    album_col = column("album name", "album")
    duration_col = column("duration (ms)", "duration_ms", "duration")
    added_col = column("added at", "added_at", "date added")
    if not name_col or not artist_col:
        sys.exit(f"No reconozco las columnas del CSV: {list(rows[0].keys())}")

    tracks = []
    for index, row in enumerate(rows):
        name = (row.get(name_col) or "").strip()
        if not name:
            continue
        duration = row.get(duration_col) if duration_col else None
        tracks.append({
            "key": (row.get(uri_col) or "").strip() if uri_col else "",
            "name": name,
            "artists": [a.strip() for a in (row.get(artist_col) or "").split(",") if a.strip()],
            "album": (row.get(album_col) or "").strip() if album_col else "",
            "duration_s": int(duration) / 1000 if duration and duration.isdigit() else None,
            "added_at": (row.get(added_col) or "").strip() if added_col else "",
        })
    for index, track in enumerate(tracks):
        if not track["key"]:
            track["key"] = f"fila-{index}"
    return tracks


_BRACKETS = re.compile(r"[\(\[].*?[\)\]]")
_SUFFIX = re.compile(r"\s-\s.*(remaster|version|versión|live|en vivo|edit|mix|mono|stereo).*$", re.IGNORECASE)


def normalize(text: str) -> str:
    text = _SUFFIX.sub("", text)
    text = _BRACKETS.sub("", text)
    text = unicodedata.normalize("NFKD", text)
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = re.sub(r"[^a-z0-9 ]", " ", text.lower())
    return re.sub(r"\s+", " ", text).strip()


def similarity(a: str, b: str) -> float:
    a, b = normalize(a), normalize(b)
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    return SequenceMatcher(None, a, b).ratio()


def score_result(track: dict, result: dict) -> float:
    title_score = similarity(track["name"], result.get("title") or "")
    result_artists = " ".join(a.get("name", "") for a in result.get("artists") or [])
    artist_score = max((similarity(artist, result_artists) for artist in track["artists"]), default=0.0)
    if any(normalize(artist) and normalize(artist) in normalize(result_artists) for artist in track["artists"]):
        artist_score = max(artist_score, 0.9)

    duration_score = 0.5
    result_duration = result.get("duration_seconds")
    if track["duration_s"] and result_duration:
        diff = abs(track["duration_s"] - result_duration)
        duration_score = 1.0 if diff <= 3 else 0.6 if diff <= 10 else 0.0
    score = 0.55 * title_score + 0.35 * artist_score + 0.10 * duration_score
    # A matching title by another artist is usually a cover or a different song.
    return score if artist_score >= 0.5 else score * 0.6


def find_on_ytmusic(yt: YTMusic, track: dict) -> tuple[dict | None, float]:
    query = f"{track['name']} {track['artists'][0] if track['artists'] else ''}".strip()
    best, best_score = None, 0.0
    for search_filter in ("songs", "videos"):
        results = with_retries(lambda: yt.search(query, filter=search_filter, limit=5), "buscar")
        for result in results[:5]:
            if not result.get("videoId"):
                continue
            score = score_result(track, result)
            if score > best_score:
                best, best_score = result, score
        if best_score >= SURE_SCORE:
            break
        time.sleep(SEARCH_PAUSE_S)
    return best, best_score


def cmd_buscar(args) -> None:
    csv_path = Path(args.csv)
    if not csv_path.exists():
        sys.exit(f"No encuentro el archivo {csv_path}")
    tracks = read_spotify_csv(csv_path)
    # Igual que en Spotify: las agregadas más recientemente primero.
    tracks.sort(key=lambda t: t["added_at"], reverse=True)

    progress = load_progress()
    progress["order"] = [t["key"] for t in tracks]
    for track in tracks:
        progress["tracks"].setdefault(track["key"], track)
    save_progress(progress)

    pending = [t for t in tracks if "video_id" not in progress["tracks"][t["key"]]]
    if args.limite:
        pending = pending[: args.limite]
    total = len(tracks)
    done = total - len([t for t in tracks if "video_id" not in progress["tracks"][t["key"]]])
    print(f"{total} canciones en el CSV · {done} ya buscadas · {len(pending)} por buscar ahora.")

    yt = get_client()
    started = time.time()
    try:
        for count, track in enumerate(pending, start=1):
            result, score = find_on_ytmusic(yt, track)
            entry = progress["tracks"][track["key"]]
            if result and score >= ACCEPT_SCORE:
                entry.update({
                    "video_id": result["videoId"],
                    "score": round(score, 3),
                    "yt_title": result.get("title"),
                    "yt_artists": ", ".join(a.get("name", "") for a in result.get("artists") or []),
                })
                mark = "✓" if score >= SURE_SCORE else "?"
            else:
                entry.update({"video_id": None, "score": round(score, 3)})
                mark = "✗"
            artists = ", ".join(track["artists"])
            print(f"[{done + count}/{total}] {mark} {track['name']} — {artists}")
            if count % 25 == 0:
                save_progress(progress)
                elapsed = time.time() - started
                remaining = (len(pending) - count) * elapsed / count
                print(f"  (guardado · faltan aprox. {remaining / 60:.0f} min)")
            time.sleep(SEARCH_PAUSE_S)
    except KeyboardInterrupt:
        print("\nPausado. Vuelve a correr el mismo comando para continuar.")
    finally:
        save_progress(progress)
    print_summary(progress)


def print_summary(progress: dict) -> None:
    entries = [progress["tracks"][k] for k in progress["order"]]
    searched = [e for e in entries if "video_id" in e]
    found = [e for e in searched if e["video_id"]]
    doubtful = [e for e in found if e["score"] < SURE_SCORE]
    print(f"\nResumen: {len(searched)}/{len(entries)} buscadas · {len(found)} encontradas "
          f"({len(doubtful)} dudosas) · {len(searched) - len(found)} no encontradas.")


# ---------- reporte ----------

def cmd_reporte(_args) -> None:
    progress = load_progress()
    rows = []
    for key in progress["order"]:
        entry = progress["tracks"][key]
        if "video_id" not in entry:
            continue
        if entry["video_id"] is None:
            status = "NO ENCONTRADA"
        elif entry["score"] < SURE_SCORE:
            status = "DUDOSA"
        else:
            continue
        rows.append({
            "estado": status,
            "cancion_spotify": entry["name"],
            "artistas_spotify": ", ".join(entry["artists"]),
            "encontrada_en_ytmusic": entry.get("yt_title") or "",
            "artistas_ytmusic": entry.get("yt_artists") or "",
            "puntaje": entry["score"],
            "enlace": f"https://music.youtube.com/watch?v={entry['video_id']}" if entry["video_id"] else "",
        })
    with REPORT_FILE.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=list(rows[0].keys()) if rows else ["estado"])
        writer.writeheader()
        writer.writerows(rows)
    print(f"Escribí {len(rows)} filas en {REPORT_FILE.name} (ábrelo con Excel).")
    print_summary(progress)


# ---------- subir ----------

def cmd_subir(args) -> None:
    progress = load_progress()
    entries = [progress["tracks"][k] for k in progress["order"]]
    if any("video_id" not in e for e in entries):
        print("Ojo: aún hay canciones sin buscar; solo subo las que ya se buscaron.")

    seen, video_ids = set(), []
    for entry in entries:
        video_id = entry.get("video_id")
        if video_id and video_id not in seen:
            seen.add(video_id)
            video_ids.append(video_id)
    if not video_ids:
        sys.exit("No hay canciones encontradas para subir. Corre primero 'buscar'.")

    parts = math.ceil(len(video_ids) / MAX_PER_PLAYLIST)
    per_part = math.ceil(len(video_ids) / parts)
    chunks = [video_ids[i:i + per_part] for i in range(0, len(video_ids), per_part)]
    print(f"{len(video_ids)} canciones → {len(chunks)} playlist(s) de ~{per_part}.")

    yt = get_client()
    for number, chunk in enumerate(chunks, start=1):
        title = PLAYLIST_TITLE if len(chunks) == 1 else f"{PLAYLIST_TITLE} ({number}/{len(chunks)})"
        if len(progress["playlists"]) < number:
            playlist_id = with_retries(
                lambda: yt.create_playlist(title, "Importada desde mis Me gusta de Spotify", privacy_status="PRIVATE"),
                "crear la playlist",
            )
            if not isinstance(playlist_id, str):
                sys.exit(f"YouTube Music no creó la playlist: {playlist_id}")
            progress["playlists"].append({"title": title, "id": playlist_id, "added": 0})
            save_progress(progress)
            print(f"Creé la playlist '{title}'.")
        playlist = progress["playlists"][number - 1]

        while playlist["added"] < len(chunk):
            batch = chunk[playlist["added"]: playlist["added"] + BATCH_SIZE]
            response = with_retries(
                lambda: yt.add_playlist_items(playlist["id"], batch, duplicates=False),
                "agregar canciones",
            )
            if isinstance(response, dict) and response.get("status") not in (None, "STATUS_SUCCEEDED"):
                print(f"  ! Respuesta inesperada: {response.get('status')}")
            playlist["added"] += len(batch)
            save_progress(progress)
            print(f"  {playlist['title']}: {playlist['added']}/{len(chunk)}")
            time.sleep(1)
        print(f"https://music.youtube.com/playlist?list={playlist['id']}")

    if args.me_gusta:
        liked = set(progress["liked"])
        pending = [v for v in video_ids if v not in liked]
        print(f"Marcando {len(pending)} canciones como 'Me gusta'...")
        try:
            for count, video_id in enumerate(pending, start=1):
                with_retries(lambda: yt.rate_song(video_id, LikeStatus.LIKE), "dar me gusta")
                progress["liked"].append(video_id)
                if count % 50 == 0:
                    save_progress(progress)
                    print(f"  {count}/{len(pending)}")
                time.sleep(SEARCH_PAUSE_S)
        except KeyboardInterrupt:
            print("\nPausado. Vuelve a correr 'subir --me-gusta' para continuar.")
        finally:
            save_progress(progress)
    print("\nListo. En Metrolist Plus las playlists aparecen al sincronizar tu Biblioteca.")


def main() -> None:
    parser = argparse.ArgumentParser(description="Pasa tus 'Me gusta' de Spotify a YouTube Music.")
    commands = parser.add_subparsers(dest="comando", required=True)
    configurar = commands.add_parser("configurar", help="Conectar tu cuenta de YouTube Music")
    configurar.add_argument("--archivo", help="Leer los encabezados copiados desde un archivo de texto")
    configurar.set_defaults(func=cmd_configurar)
    buscar = commands.add_parser("buscar", help="Buscar las canciones del CSV en YouTube Music")
    buscar.add_argument("csv", help="Archivo CSV exportado con Exportify")
    buscar.add_argument("--limite", type=int, default=0, help="Buscar solo N canciones (para probar)")
    buscar.set_defaults(func=cmd_buscar)
    commands.add_parser("reporte", help="Crear reporte.csv con no encontradas y dudosas").set_defaults(func=cmd_reporte)
    subir = commands.add_parser("subir", help="Crear las playlists y agregar las canciones")
    subir.add_argument("--me-gusta", action="store_true", help="Además marcarlas como 'Me gusta'")
    subir.set_defaults(func=cmd_subir)
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
