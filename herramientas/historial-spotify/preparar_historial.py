"""
Metrolist Plus — convierte el historial extendido de Spotify en un archivo que la app importa.

Uso:
    python preparar_historial.py "<carpeta o ZIP de Spotify>" [salida.db]

Lee los Streaming_History_Audio_*.json (y los de Video que sean canciones) y genera
historial_spotify.db. No guarda IP, país ni datos de incógnito.
"""

import io
import json
import os
import re
import sqlite3
import sys
import zipfile
from collections import Counter
from datetime import datetime, timezone

FORMATO = 1
PATRON = re.compile(r"Streaming_History_(Audio|Video)_.*\.json$", re.IGNORECASE)
PATRON_CUENTA = re.compile(r"StreamingHistory(_music)?_?\d*\.json$", re.IGNORECASE)

# Valores de reason_end que se guardan como número para ahorrar espacio.
RAZONES = ["trackdone", "endplay", "fwdbtn", "backbtn", "logout", "remote", "unexpected-exit",
           "unexpected-exit-while-paused", "clickrow", "playbtn", "appload", "trackerror"]


def plataforma(texto):
    t = (texto or "").lower()
    if "android" in t:
        return "android"
    if t.startswith("windows"):
        return "windows"
    if t.startswith("osx") or t.startswith("os x") or "macos" in t or "mac os" in t:
        return "mac"
    if t.startswith("ios") or "iphone" in t or "ipad" in t:
        return "ios"
    if "web" in t or "chrome" in t:
        return "web"
    if "cast" in t or "speaker" in t or "tv" in t or "sonos" in t or "partner" in t:
        return "bocina/tv"
    return "otro"


def archivos(origen):
    """Devuelve (nombre, bytes) de cada JSON de historial, desde carpeta o ZIP."""
    if os.path.isdir(origen):
        for raiz, _, nombres in os.walk(origen):
            for n in sorted(nombres):
                ruta = os.path.join(raiz, n)
                if PATRON.search(n):
                    with open(ruta, "rb") as f:
                        yield n, f.read()
                elif PATRON_CUENTA.search(n):
                    print(f"  (se ignora {n}: es el historial del último año, ya viene en el extendido)")
    elif zipfile.is_zipfile(origen):
        with zipfile.ZipFile(origen) as z:
            for n in sorted(z.namelist()):
                base = os.path.basename(n)
                if PATRON.search(base):
                    yield base, z.read(n)
                elif PATRON_CUENTA.search(base):
                    print(f"  (se ignora {base}: es el historial del último año, ya viene en el extendido)")
    else:
        sys.exit(f"No encuentro la carpeta o el ZIP: {origen}")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    origen = sys.argv[1]
    salida = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(origen)), "historial_spotify.db")
    if os.path.exists(salida):
        os.remove(salida)

    db = sqlite3.connect(salida)
    db.executescript("""
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE artist (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, artist_key TEXT, thumb TEXT);
        CREATE TABLE album (id INTEGER PRIMARY KEY, name TEXT NOT NULL, artist_id INTEGER NOT NULL, UNIQUE(name, artist_id));
        CREATE TABLE track (id INTEGER PRIMARY KEY, uri TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
            artist_id INTEGER NOT NULL, album_id INTEGER, track_key TEXT, yt_id TEXT);
        CREATE TABLE play (id INTEGER PRIMARY KEY, source INTEGER NOT NULL DEFAULT 0, ts_utc INTEGER NOT NULL,
            ts_local INTEGER, ms INTEGER NOT NULL, track_id INTEGER NOT NULL, skipped INTEGER, shuffle INTEGER,
            platform TEXT, reason_end INTEGER);
    """)

    artistas, albumes, canciones = {}, {}, {}
    filas = []
    vistos = set()
    descartadas = 0
    horas_anio = Counter()
    horas_artista = Counter()

    def id_de(tabla, cache, clave, sql, valores):
        if clave not in cache:
            cache[clave] = db.execute(sql, valores).lastrowid
        return cache[clave]

    for nombre, contenido in archivos(origen):
        datos = json.load(io.TextIOWrapper(io.BytesIO(contenido), encoding="utf-8"))
        print(f"  {nombre}: {len(datos):,} registros")
        for e in datos:
            uri = e.get("spotify_track_uri")
            titulo = e.get("master_metadata_track_name")
            artista = e.get("master_metadata_album_artist_name")
            if not uri or not titulo or not artista:
                descartadas += 1
                continue
            ts = int(datetime.strptime(e["ts"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).timestamp() * 1000)
            ms = int(e.get("ms_played") or 0)
            # Spotify a veces repite el mismo registro entre archivos.
            if (ts, uri, ms) in vistos:
                continue
            vistos.add((ts, uri, ms))

            a_id = id_de("artist", artistas, artista, "INSERT INTO artist(name) VALUES (?)", (artista,))
            album = e.get("master_metadata_album_album_name")
            al_id = None
            if album:
                al_id = id_de("album", albumes, (album, a_id), "INSERT INTO album(name, artist_id) VALUES (?, ?)", (album, a_id))
            t_id = id_de("track", canciones, uri, "INSERT INTO track(uri, name, artist_id, album_id) VALUES (?, ?, ?, ?)",
                         (uri, titulo, a_id, al_id))
            razon = e.get("reason_end")
            filas.append((ts, ms, t_id,
                          None if e.get("skipped") is None else int(bool(e["skipped"])),
                          None if e.get("shuffle") is None else int(bool(e["shuffle"])),
                          plataforma(e.get("platform")),
                          RAZONES.index(razon) if razon in RAZONES else None))
            horas_anio[e["ts"][:4]] += ms
            horas_artista[artista] += ms

    if not filas:
        sys.exit("No encontré reproducciones. ¿Es la carpeta del historial EXTENDIDO (Streaming_History_Audio_*.json)?")

    filas.sort()
    db.executemany("INSERT INTO play(ts_utc, ms, track_id, skipped, shuffle, platform, reason_end) VALUES (?,?,?,?,?,?,?)", filas)
    total_ms = sum(f[1] for f in filas)
    meta = {
        "formato": FORMATO,
        "creado": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "reproducciones": len(filas),
        "canciones": len(canciones),
        "artistas": len(artistas),
        "horas": round(total_ms / 3.6e6),
        "desde": filas[0][0],
        "hasta": filas[-1][0],
    }
    db.executemany("INSERT INTO meta VALUES (?, ?)", [(k, str(v)) for k, v in meta.items()])
    db.commit()
    db.execute("VACUUM")
    db.close()

    print()
    print(f"Listo: {salida}  ({os.path.getsize(salida) / 1e6:.1f} MB)")
    print(f"  Reproducciones: {len(filas):,}   (descartadas, podcasts u otros: {descartadas:,})")
    print(f"  Canciones: {len(canciones):,}   Artistas: {len(artistas):,}   Horas: {total_ms / 3.6e6:,.0f}")
    print("  Horas por año: " + ", ".join(f"{a}: {round(m / 3.6e6)}" for a, m in sorted(horas_anio.items())))
    print("  Top artistas: " + ", ".join(f"{a} ({round(m / 3.6e6)} h)" for a, m in horas_artista.most_common(5)))


if __name__ == "__main__":
    main()
