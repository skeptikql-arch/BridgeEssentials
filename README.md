# BridgeEssentials 1.0 — Source Manual

BridgeEssentials is a client-side Minecraft Forge mod for Minecraft 1.8.9. It provides a bridging-practice HUD with attempt timing, landing information, coordinates, forecasts, and configurable display options.

## Features

- Attempt timer and attempt counter.
- Tap, Flat, Speed, and failed-landing information.
- Opening-flat detection and landing-coordinate tracking.
- Turnout forecasts.
- Pressure-plate coordinates and bed-click timing.
- Jump-tick information and saved best-coordinate statistics.
- An in-game settings menu for HUD sections, layout, appearance, and keybind options.

The mod is client-side. It does not require a custom server plugin.

## Requirements

- **Minecraft:** 1.8.9
- **Minecraft Forge:** 1.8.9, build `11.15.1.2318`
- **Java:** Java 8 JDK (not just a JRE)
- An internet connection for the first build so Gradle and the required Forge dependencies can be downloaded.

This project uses the Gradle 2.7 wrapper and the legacy ForgeGradle 2.1 build plugin. These are intentionally old to match the Minecraft 1.8.9 toolchain.

## Building from source

Run the commands from the folder that contains `build.gradle`.

### Windows

1. Install a Java 8 JDK.
2. Make sure `java -version` reports Java `1.8` in Command Prompt, or set `JAVA_HOME` to your Java 8 JDK folder.
3. Open Command Prompt or PowerShell in the project folder.
4. Run:

   ```bat
   .\gradlew.bat build
   ```

### Linux / macOS

1. Install a Java 8 JDK.
2. Open a terminal in the project folder.
3. Make the wrapper executable if needed:

   ```sh
   chmod +x ./gradlew
   ```

4. Run:

   ```sh
   ./gradlew build
   ```

The first run may take several minutes because Gradle downloads its distribution and ForgeGradle downloads build dependencies.

### Build output

When the build succeeds, the mod JAR is written to:

```text
build/libs/BridgeEssentials-1.0.jar
```

The source archive does not include a prebuilt JAR. Build one locally using the steps above.

## Installing the built mod

1. Make sure the Minecraft launcher has a Forge 1.8.9 installation using Forge `11.15.1.2318`.
2. Run the build and locate `build/libs/BridgeEssentials-1.0.jar`.
3. Place that JAR in the `mods` folder for the Minecraft 1.8.9 game directory.
4. Launch the Forge 1.8.9 profile.

## In-game use

- Press **Right Shift** to open the settings menu by default.
- Use the menu to enable or disable HUD sections and adjust the overlay options.
- The menu keybind can be changed in the settings menu.
- Settings are saved to the Minecraft configuration folder.

The main configuration file is:

```text
config/bridgeessentials.cfg
```

Saved best-coordinate data is stored separately in:

```text
config/bridgeessentials-bests.properties
```

## Project layout

```text
BridgeEssentials/
├── src/
│   └── main/
│       ├── java/       Java source code
│       └── resources/  Mod metadata, fonts, and GUI textures
├── gradle/wrapper/    Gradle wrapper files
├── gradlew            Gradle wrapper (Linux/macOS)
├── gradlew.bat        Gradle wrapper (Windows)
├── build.gradle       ForgeGradle build configuration
├── settings.gradle    Gradle project settings
└── README.md          This manual
```

## Troubleshooting

**`JAVA_HOME` is not set or Java cannot be found**  
Install a Java 8 JDK, then add its `bin` directory to `PATH` or set `JAVA_HOME` to the JDK installation folder. Open a new terminal after changing environment variables.

**The build uses the wrong Java version**  
Run `java -version` and verify that it reports version `1.8`. Also check `JAVA_HOME` if it is set.

**Gradle cannot download dependencies**  
Check your internet connection and try again. This project relies on legacy Gradle and ForgeGradle repositories, so availability of those older dependencies may affect whether a fresh build can be completed today.

**`No such file` / build command not found**  
Run the command from the project root, the same folder that contains `build.gradle` and `gradlew.bat`.

## Third-party notices

The source tree bundles the Inter font and its SIL Open Font License notice. The archive also retains the upstream Forge/FML and Paulscode license and credit notices supplied with the original project. Those notices apply to their respective third-party materials; they are not a license for the original BridgeEssentials code.
