# RetroSpeed 🏁

Een seventies-look snelheidsmeter-app voor Android.

## Features
- **GPS-snelheid** in km/u, live via de GPS-provider (1 seconde updates)
- **Automatische max. snelheid** via OpenStreetMap (Overpass): dichtstbijzijnde weg met `maxspeed`-tag, ook mph wordt omgerekend
- **Handmatige override**: knoppen 30 / 50 / 60 / 80 / 100 / 120 km/u
- **Groene snelheid** zodra je binnen de limiet rijdt, rood knipperend + trillen bij overschrijding
- **Overspeeding-alarm**: paneel knippert rood en de telefoon trilt zodra je boven de limiet zit
- **Seventies-design**: warme bruine/oranje gradient, dikke monospace cijfers, chunky afgeronde panelen

## Bouwen
1. Open de map in **Android Studio** (Ladybug of nieuwer aanbevolen)
2. Laat Gradle sync draaien
3. `Build > Build Bundle(s) / APK(s) > Build APK(s)` — de APK verschijnt in `app/build/outputs/apk/debug/`

Of via de command line:

```bash
./gradlew assembleDebug
```

## Rechten
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — voor de GPS-snelheid
- `VIBRATE` — voor het overspeeding-alarm
- `INTERNET` — voor het ophalen van snelheidslimieten uit OpenStreetMap

## Gebruik
Start de app, geef locatietoegang, kies de maximaal toegestane snelheid onderaan en rij maar. De teller loopt soepel mee en het paneel kleurt rood zodra je te hard gaat.
