# Auto Text

Fabric mod for Minecraft 1.8.9 (Ornithe), 1.21.1, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11, 26.1, 26.2 and 26.3.

Built with [Stonecutter](https://stonecutter.kikugie.dev/). Gradle downloads the JDKs it needs (21 and 25).

## Build

macOS / Linux:

```sh
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

Builds every version. To build one, run `./gradlew :1.21.11:build`.

Output: `versions/<minecraft version>/build/libs/autotext-0.1.0+<minecraft version>.jar`.

Runtime dependencies: Fabric Loader, Fabric Language Kotlin and OneConfig v1, as listed in `fabric.mod.json`.

License: AGPL-3.0-or-later; see `LICENSE`.
