<img src="https://user-images.githubusercontent.com/28866693/201870048-1ca6b73c-6e46-4e16-b00d-05d996c5ab92.png" width="100" alt="Ialon">

# Ialon - A block (voxel) construction game

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=vxel_ialon&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=vxel_ialon)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=vxel_ialon&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=vxel_ialon)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=vxel_ialon&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=vxel_ialon)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=vxel_ialon&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=vxel_ialon)

Ialon is a block construction game working on desktop and Android devices.
Ialon means clearing, cleared area and, by extension, village in Gallic.

It is currently in development although already functional.
It is based on the jMonkeyEgine and the Blocks framework library.

It is released under the GNU General Public License v3.0.

Use **Tab** key to switch between desktop and mobile mode.

In Desktop mode :
- Use WASD / ZQSD keys to move left, backward, right and forward
- Use the mouse to rotate the camera
- Use mouse left to add the selected block and mouse right to remove it
- Use Esc key to exit
- Use Space key to jump or to swim
- Use F key to switch the Fly mode
- In Fly mode, use the Up and Down arrow keys to go up or down.

# Features

- 400+ different blocks
- Shapes : cube, slab, wedge, pole, pyramid, stairs, corner stairs, fence, slates
- Create and switch between multiple worlds
- Night/day cycle with Time acceleration factor
- Light and fire blocks
- Water and lava simulation
- Works on desktop or Android devices
- Fly mode
- Free, Ad-free, Open Source

# Installing Ialon

## Desktop version

Self-contained installers (a trimmed Java runtime is bundled - **no Java installation required**)
are published on the [Releases page](https://github.com/vxel/ialon/releases) for each tagged version.

- **Linux** - download `ialon_<version>_amd64.deb` and install it:
  ```bash
  sudo dpkg -i ialon_<version>_amd64.deb
  ```
  The game is installed under `/opt/ialon` and appears in the applications menu (category *Game*).
  Saved worlds live in `~/.local/share/ialon` (or `$XDG_DATA_HOME/ialon`).

- **Windows** - download `Ialon-<version>.exe`, run it and follow the installer. A Start-menu and
  desktop shortcut are created. Saved worlds live in `%APPDATA%\Ialon`.

The Android version is not distributed as an installer yet (see *Android Version* below).

## Android Version

Currently, you need android-studio to import the projet, build the code, upload the apk on a connected Android device and to run it.
In Fly mode, use the Jump + Forward or Backward buttons to go up or down.

# Screenshots

## Version 0.7.0

- Far horizon and improved world generation
  ![Screenshot Ialon 6](https://github.com/user-attachments/assets/80c2283e-e6b4-4630-b1f1-d9ae1dd24168)
- Lava
  ![Screenshot Ialon 8](https://github.com/user-attachments/assets/d44c7edb-0d23-48bc-9eea-da51887f1c10)
- Supports multiple worlds :
  ![Screenshot Ialon 9](https://github.com/user-attachments/assets/67db1101-3da2-4a97-854e-2d5eaa231ef8)

## Version 0.5.0

- New font for the User Interface
- New Setup menu
- Soft Shadows for wedge blocks (see the roof in the following screenshot) :
![Screenshot Ialon 6](https://github.com/vxel/ialon/assets/28866693/8a9fb54d-3a46-46c7-b3fd-27ee34e49bdf)

## Version 0.4.0

New block selection popup and new blocks (bed, drawers, oven, books...)

![Screenshot Ialon 6](https://user-images.githubusercontent.com/28866693/254984090-0d28e49b-0e87-4d23-a0b4-d3db6dadf6e4.jpg)

## Version 0.3.0

Rail system 

![Screenshot Ialon 5](https://user-images.githubusercontent.com/28866693/254984107-282fff7a-4b6f-4cda-9d68-f82e60bf4580.jpg)

## Version 0.2.0

Soft shadows

![Screenshot Ialon 4](https://user-images.githubusercontent.com/28866693/249278557-8a9ee388-2e61-4eeb-8c6c-b24d0ee17f75.jpg)

## Version 0.1.0

Water simulation and Minecraft-like lightning :

![Screenshot Ialon 1](https://user-images.githubusercontent.com/28866693/201869015-77123bbf-38dd-4d5c-b481-cc0be6395673.jpg)

![Screenshot Ialon 2](https://user-images.githubusercontent.com/28866693/196793101-70fb77e4-5b72-4677-a85b-19f3540e905c.png)

![Screenshot Ialon 3](https://user-images.githubusercontent.com/28866693/196790246-a1cf2706-edfb-4e7f-b533-ca147a58a68b.jpeg)

# Developer

[![Build Status](https://github.com/vxel/ialon/workflows/Build%20Ialon/badge.svg)](https://github.com/vxel/ialon/actions)

## Building the installers

The `:desktop` module uses the [Beryx runtime plugin](https://github.com/beryx/badass-runtime-plugin)
to assemble a minimal JRE (via `jlink`) and a native installer (via `jpackage`). A **full JDK 21**
is required (it must provide `jpackage` and `jmods`).

```bash
# Linux (.deb) — needs dpkg-deb + fakeroot (present on Ubuntu)
./gradlew :desktop:jpackage -PinstallerType=deb -PappVersion=1.0.0 --no-configuration-cache

# Windows (.exe) — needs the WiX Toolset v3
./gradlew :desktop:jpackage -PinstallerType=exe -PappVersion=1.0.0 --no-configuration-cache
```

`--no-configuration-cache` is required (the `jpackage`/`runtime` tasks are not compatible with the
configuration cache). The installer is written to `desktop/build/jpackage/`. Note that `jpackage`
cannot cross-compile: a Windows `.exe` can only be built on Windows, and a `.deb` on Linux.

Packaged builds are started with `-Dialon.packaged=true`, which redirects saved worlds and native
library extraction to a per-user, writable directory (see *Installing Ialon* above), so the app
works when installed in a read-only location such as `/opt`.

## Publishing a release

Pushing a version tag triggers the [`Release`](.github/workflows/release.yml) workflow, which builds
the Linux and Windows installers on their respective runners and attaches them to a GitHub Release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Cédric de Launois
