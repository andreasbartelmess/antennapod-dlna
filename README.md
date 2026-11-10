# AntennaPod + DLNA

Private Variante von [AntennaPod](https://github.com/AntennaPod/AntennaPod) (F-Droid-Ausgabe, „free“-Flavor)
mit Wiedergabe auf DLNA/UPnP-Renderern (z. B. gmediarender, Hi-Fi-Streamer, AV-Receiver).

Kein offizielles AntennaPod-Projekt. Alle Rechte am Original liegen bei den AntennaPod-Entwicklern,
Lizenz GPL-3.0 (siehe Branch `dlna`).

## Aufbau

| Branch | Inhalt |
|---|---|
| `dlna` | AntennaPod-Quellcode (letztes stabiles Release) + DLNA-Commits |
| `main` | Diese Anleitung und der Build-Workflow |

Die DLNA-Funktion ersetzt nur die leeren Chromecast-Platzhalter der F-Droid-Variante
(`playback/cast/src/free/`), dadurch lassen sich neue AntennaPod-Versionen fast immer konfliktfrei übernehmen.

## Automatische Updates

Der Workflow [`release.yml`](.github/workflows/release.yml) läuft täglich:

1. holt das neueste stabile AntennaPod-Release und setzt die DLNA-Commits darauf (`git rebase`),
2. baut eine signierte APK und veröffentlicht sie unter [Releases](../../releases).

Bei einem Konflikt schlägt der Workflow fehl (GitHub schickt eine Mail); die bisherige Version bleibt gültig.
Manuell starten: *Actions → AntennaPod DLNA bauen → Run workflow*.

Benötigte Secrets: `KEYSTORE_BASE64` (PKCS12-Keystore, Alias `antennapod-dlna`), `STORE_PASSWORD`,
`FDROID_KEYSTORE_BASE64` (Alias `fdroid-repo`), `FDROID_STORE_PASSWORD`.

## Auf dem Gerät aktuell halten

**F-Droid:** Der Workflow pflegt ein eigenes F-Droid-Repo auf GitHub Pages:
<https://andreasbartelmess.github.io/antennapod-dlna/> → „In F-Droid hinzufügen“.
Danach in F-Droid bei AntennaPod dieses Repo als bevorzugte Quelle wählen
(das offizielle F-Droid-Repo bietet AntennaPod mit anderer Signatur an).

Alternativ [Obtainium](https://github.com/ImranR98/Obtainium) mit der URL dieses Repos.

## Bedienung

Im großen Player oben auf das Cast-Symbol tippen → DLNA-Gerät wählen.
„Dieses Gerät“ schaltet zurück. Pause, Spulen und Lautstärke (auch über die Lautstärketasten)
steuern dann den Renderer.
