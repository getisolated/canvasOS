# Third-party notices

- **Inter** typeface (`app/src/main/assets/inter.ttf`) — Copyright (c) 2016 The Inter Project Authors, SIL Open Font License 1.1. Full text: `app/src/main/assets/inter-OFL.txt`.
- **Material Icons** (vector paths in `app/src/main/res/drawable/ic_*.xml`), including the Material Symbols weather icons (`ic_wx_*.xml`: sunny, clear_night, partly_cloudy_day, partly_cloudy_night, cloud, rainy, weather_snowy, thunderstorm, foggy) — Copyright Google LLC, Apache License 2.0, https://github.com/google/material-design-icons.
- **dadb** (build dependency) — Copyright mobile.dev, Apache License 2.0.
- **AndroidX Media3 ExoPlayer** (build dependency) — Copyright The Android Open Source Project, Apache License 2.0.
- **NanoHTTPD** (build dependency) — Copyright (c) 2012-2013 by Paul S. Hawke, 2001,2005-2013 by Jarno Elonen, 2010 by Konstantinos Togias, BSD 3-Clause License.
- **ZXing core** (build dependency) — Copyright ZXing authors, Apache License 2.0.
- **Aerial** (https://github.com/pengchujin/aerial-tv) — MIT License, Copyright (c) 2026 pengchujin. canvasOS ports its home guard and revival (`Guard.kt`), Xiaomi device details (`tv/XiaomiAdapter.kt`, `AppRepo.kt`), the phone page (`PhoneServer.kt`, `assets/web/index.html`, restyled), `tools/tcast-push.py`, and ships its aerial film catalogue `app/src/main/assets/aerials.json` (addresses and place names from Apple's public tvOS aerial resources; the films themselves stream from Apple and are not included).
- **Apple Root CA** (`app/src/main/res/raw/apple_root_ca.pem`) — Apple's public root certificate, trusted only for `sylvan.apple.com`, which serves the aerial films.

## Data sources

The photo backgrounds and the photo frame download pictures at run time from these services (nothing is bundled); each picture is shown with the credit its source gives.

- **Wikimedia Commons**, Picture of the Day (https://commons.wikimedia.org/w/api.php): each file under its own licence (mostly CC BY-SA or public domain), author and licence shown in the caption; Wikimedia Terms of Use and User-Agent policy.
- **NASA Astronomy Picture of the Day** (https://science.nasa.gov/apod/, backup https://api.nasa.gov with its DEMO_KEY): NASA's own images are public domain, others © their authors as credited by APOD; NASA media usage guidelines.
- **The Metropolitan Museum of Art** Open Access API (https://metmuseum.github.io/): public-domain works only, images released under CC0.
- **Art Institute of Chicago** API and IIIF images (https://api.artic.edu/docs/): public-domain works only, data under CC0; terms at https://www.artic.edu/terms.
- **Cleveland Museum of Art** Open Access API (https://openaccess-api.clevelandart.org/): CC0 works only.
- **Wallhaven** API (https://wallhaven.cc/help/api): SFW wallpapers uploaded by its users; Wallhaven's terms of use.
- **TheCatAPI** (https://thecatapi.com) and **Dog CEO** (https://dog.ceo/dog-api/): free tiers without a key; pictures as published by each service.
- **Lorem Picsum** (https://picsum.photos): photos from Unsplash under the Unsplash License.
- **Bing** daily images (https://www.bing.com): © their photographers, shown with Bing's credit.
- **360 wallpapers** (wallpaper.apc.360.cn): last-resort backup feed; pictures © their owners.

The weather (`Weather.kt`) comes from these services, asked by the TV itself:

- **Open-Meteo** forecast and geocoding APIs (https://open-meteo.com): free for non-commercial use without a key; weather data under CC BY 4.0, credited in the Control Center's forecast (« Prévisions : Open-Meteo.com »).
- **ip-api.com** (http://ip-api.com): free for non-commercial use; asked once for the city of the TV's public address while no city has been chosen.
