<p align="center">
  <img src="docs/images/miauterm-terminal.png" alt="Miauterm terminal icon" width="88" height="88">
</p>

<h1 align="center">Miauterm</h1>

<p align="center">An experimental Android terminal that runs Debian ARM64 in PRoot.</p>

<table align="center">
  <tbody>
    <tr>
      <td align="center"><a href="https://github.com/Kandoraq/miauterm/releases/latest"><img alt="Latest release version" src="https://img.shields.io/github/v/release/Kandoraq/miauterm?style=for-the-badge&amp;label=Latest%20release"></a></td>
      <td align="center"><img alt="Android ARM64" src="https://img.shields.io/badge/Android-arm64--v8a-3c7d5b?style=for-the-badge&amp;logo=android&amp;logoColor=white"></td>
    </tr>
    <tr>
      <td align="center" colspan="2"><a href="https://github.com/Kandoraq/miauterm/actions/workflows/build-apk.yml?query=branch%3Amain"><img alt="Build status on main" src="https://img.shields.io/github/actions/workflow/status/Kandoraq/miauterm/build-apk.yml?branch=main&amp;event=push&amp;style=for-the-badge&amp;label=Build%20status"></a></td>
    </tr>
  </tbody>
</table>

> **Project status: Experimental**<br>
> The current app version is **0.1.5-coral** (codename **Coral**). The experimental APK targets ARM64 Android devices; runtime behavior and package compatibility can vary by device and Android version.

Miauterm is an unofficial fork of [Cortex Terminal](https://github.com/ssfuisu/cortex). It retains upstream terminal and Android application code while replacing Cortex's native Ubuntu runtime with a Debian 13 (Trixie) ARM64 root filesystem launched through PRoot. Miauterm is an independent project and is not affiliated with or endorsed by the Cortex maintainers.

## Why PRoot

[Cortex Terminal](https://github.com/ssfuisu/cortex) runs Ubuntu natively on Android using its own userspace integration. Its upstream README describes that design as running without PRoot, a virtual machine, CPU emulation, or Android root access.

Miauterm uses PRoot to run Debian userspace on Android. A conventional Linux distribution expects its own user and group databases, filesystem layout, permissions, and package tools. Android's app environment does not provide those pieces as a standard distribution root filesystem.

PRoot lets Miauterm use Debian's own root filesystem, including `/etc/passwd`, the group database, and `apt`, without requiring root access to Android. Miauterm does not emulate an ARM CPU; the bundled Debian system and its native programs target `arm64-v8a` devices.

| | Cortex Terminal | Miauterm |
|---|---|---|
| Linux userspace | Ubuntu 24.04, as described by upstream | Debian 13 (Trixie) ARM64 |
| Runtime | Native Android userspace integration | PRoot |
| CPU emulation | None | None for the ARM64 build |
| Android root required | No | No |
| Android security boundary | Android application sandbox | Android application sandbox |

## App isolation, Android access, and network ports

Miauterm keeps the Debian root filesystem in the app's private files directory. A PRoot session binds only Android's `/dev`, `/proc`, and `/sys` pseudo-filesystems for terminal devices, process information, and hardware reporting. Download and Documents are separate, optional shared-folder binds that require the user to enable access in Settings. Android `/system`, `/data`, and general shared storage are not bound into Debian.

PRoot does not provide Android root privileges or a separate kernel mount namespace. Miauterm has no real `chroot` mode. The Debian `root` account is a PRoot-simulated identity and cannot gain Android root privileges.

Miauterm maps guest server binds from port `80` to `2080` and `443` to `2443` using a small ARM64 glibc interposer loaded only inside Debian processes. Guest loopback connections to `80` and `443` follow the same mapping; connections to remote hosts are unchanged. The same guest-only library returns the configured Debian hostname to `gethostname()` and `uname()`, so tools such as Fastfetch see the guest name without changing Android's kernel hostname. This lets ordinary user-space programs use familiar values without Android root. Servers actually listen on `2080` and `2443`, which Miauterm uses in local server notifications. HTTPS detection accepts a local TLS handshake for service discovery; browser certificate trust remains the server's responsibility.

When a session exits, PRoot's `--kill-on-exit` option and Miauterm's PTY process-group cleanup stop session children. Android can still retain a detached process that deliberately creates a new session of its own; PRoot cannot control processes that escape its process group.

## Compatibility trade-offs

PRoot does not boot a second Linux kernel. It uses `ptrace` to present a guest filesystem and translate guest process requests to the Android host kernel. This model brings the following compatibility limits; see the [PRoot overview](https://proot-me.github.io/) and [PRoot manual](https://github.com/proot-me/proot/blob/master/doc/proot/manual.rst).

- **Some workloads run slower.** PRoot adds work while tracing processes and translating filesystem and system calls. Package installation, compilation, and commands that perform many small file operations can take longer than they would on a native Linux system. The impact depends on the command and device.
- **Privileged operations can fail.** PRoot can simulate some filesystem behavior for compatibility, but it cannot load kernel modules or mount filesystems that require real kernel privileges.
- **The Android kernel sets the limits.** Debian programs use the Android host kernel, not a Debian kernel. Software that depends on a particular kernel feature, device, mount, namespace, or cgroup may not work. A full machine boot with systemd and kernel-managed services is outside Miauterm's runtime model.
- **Architecture support is limited to ARM64.** Miauterm bundles an ARM64 Debian rootfs and does not configure QEMU user-mode emulation for foreign binaries. Installing x86 or x86-64 packages therefore does not make their binaries runnable in this build.
- **Tracing and nested runtimes can be restricted.** PRoot itself uses `ptrace`, so debuggers, profilers, nested PRoot environments, and container tools that need their own tracing or kernel features may have limitations.
- **Networking belongs to the Android app.** Debian uses the app's network connection rather than a separate virtual network interface. Android VPN, DNS, firewall, and background restrictions can affect package downloads and other network commands.
- **Linux users are not separate Android users.** Debian's root and regular users share Miauterm's Android app identity and private storage boundary. Creating a Debian user does not create a separate Android security identity.

Package compatibility depends on the program's kernel requirements, architecture, and behavior under PRoot. A package installing successfully does not guarantee that every feature it provides will work on every Android device. This trade-off is intentional: Miauterm prioritizes Debian compatibility over native execution.

## Features

- Bundled Debian 13 ARM64 root filesystem with first-run extraction progress.
- Up to eight named terminal sessions, a failsafe shell, and Debian reset, backup, and restore tools.
- Rootless Debian port mapping from guest ports `80`/`443` to Android-compatible `2080`/`2443`.
- Manual GitHub release checks with an Android notification linking to the release page; APK download stays in the browser.
- File browser for the app's Linux files, with file type and write-permission indicators.
- Optional access to Android's Download and Documents folders. Access is off until enabled by the user.
- Terminal color schemes, font and text-size selection, custom font reload, and configurable extra keys.
- OSC 52 clipboard writes from Debian programs are disabled until enabled in Settings.
- User selection for Debian sessions. An empty selection starts a root PRoot session; configured users are selected by their exact Debian username.
- Custom DNS servers and Debian hostname settings, plus PRoot diagnostics and optional logs.
- An in-app release check and a GitHub issue link for bug reports.
- `miauterm-cmd` for commands that connect a Debian shell to selected Miauterm app functions.

These features use Miauterm changes and retained Cortex components. See [Credits](#credits) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) for attribution and component notices.

## Requirements and storage

| Requirement | Details |
|---|---|
| Android version | Android 7.0 (API 24) or newer |
| Target SDK | 28 |
| CPU architecture | `arm64-v8a` |
| Linux distribution | Bundled Debian 13 (Trixie) ARM64 root filesystem |
| App version | 0.1.5-coral |
| App language | English only |

Miauterm stores Debian inside its app-private files directory and extracts the bundled root filesystem on first start. The first launch shows extraction progress before opening the Debian shell. Leave enough device storage for the extracted system and packages you install. `apt update` and package installation require network access.

If enabled in Settings, only the selected Download and Documents locations are shared with new Linux sessions under `~/android/`. Miauterm does not give Debian general access to Android storage.

## Install and start Debian

1. Download the signed ARM64 APK from the release page and install it on an ARM64 Android device.
2. Open Miauterm and wait for Debian extraction to finish. Keep the app open during the first setup.
3. The terminal opens a Debian shell when setup completes. Android notification permission controls Miauterm notifications; shared-folder permission is separate and optional.
4. To let Debian access Android Download or Documents, enable **Download and Documents access** in Settings and grant Android permission. Start a new session to apply permission changes.

Miauterm uses the selected Debian username when it exists in the rootfs. If the username field is empty, the session starts as Debian `root`. That account is virtual inside PRoot and has no Android root access.

## Replacing the Debian rootfs from Failsafe

Miauterm currently accepts Debian ARM64 rootfs files only. It checks `etc/os-release` for `ID=debian`, validates an ARM64 shell and required Debian commands, and may reinstall the bundled Debian system if those checks fail. Alpine, Arch, Ubuntu, and other non-Debian root filesystems are not supported by the normal session launcher.

Failsafe opens Android's shell in Miauterm's private files directory. It is a recovery shell, not a Linux session, and it does not include a general rootfs archive picker. The archive must already be available in that private directory before extraction. An archive kept only in Android Downloads is not automatically visible to Failsafe, and the current app has no user-facing importer for arbitrary rootfs archives.

To try a different Debian ARM64 rootfs:

1. Export a Debian backup from **Settings > Linux system** and close active Debian sessions.
2. Obtain a Debian ARM64 rootfs from a source you trust. Place an uncompressed `.tar` archive named `rootfs.tar` in the private files directory shown by the Failsafe prompt. The archive must contain top-level paths such as `etc/`, `usr/`, and `var/`, rather than an extra wrapper directory.
3. Open **Settings > Linux system > Rootfs and symlink guide**, then start Failsafe. Check that the archive is present and inspect its layout:

   ```sh
   pwd
   ls -l rootfs.tar
   tar -tf rootfs.tar | head
   ```

4. Extract to a staging directory outside the active `debian/` directory:

   ```sh
   mkdir -p "$PWD/debian-staging"
   tar -xf "$PWD/rootfs.tar" -C "$PWD/debian-staging"
   test -f "$PWD/debian-staging/etc/os-release"
   grep -E '^(ID|PRETTY_NAME)=' "$PWD/debian-staging/etc/os-release"
   ```

   Confirm that `ID=debian` appears and that the extracted tree has `usr/` and the expected top-level directories. Miauterm also needs an executable ARM64 `bash` or `sh`, plus `apt`, `apt-get`, `dpkg`, `passwd`, `useradd`, `groupadd`, `userdel`, `usermod`, and `chown`. The app validates these files when Debian starts.

5. Follow the symlink section in the in-app guide. Miauterm expects `/bin -> usr/bin`, `/sbin -> usr/sbin`, and `/lib -> usr/lib`; it repairs ordinary Debian usr-merge layouts when it can. Do not delete existing directories to force links. Conflicts or missing targets can prevent startup.
6. After checking the staged tree, preserve the current installation and switch the directory names from Failsafe:

   ```sh
   mv "$PWD/debian" "$PWD/debian-previous"
   mv "$PWD/debian-staging" "$PWD/debian"
   ```

   Keep the exported backup and `rootfs.tar` until the new system starts successfully. If startup validation fails, Miauterm can restore its bundled Debian on the next initialization. To roll back before that happens, move the failed `debian/` aside and rename `debian-previous/` back to `debian/`.

This is a manual recovery path, not a supported distribution importer. The bundled Debian backup can be restored from Settings. A non-Debian rootfs will fail Miauterm's Debian checks and should not replace the working system.

## Download

Download the signed ARM64 APK from the [Miauterm 0.1.5-coral release](https://github.com/Kandoraq/miauterm/releases/tag/v0.1.5-coral):

- [Installable release APK](https://github.com/Kandoraq/miauterm/releases/download/v0.1.5-coral/Miauterm-v0.1.5-coral-arm64.apk)
- [Release APK SHA-256](https://github.com/Kandoraq/miauterm/releases/download/v0.1.5-coral/Miauterm-v0.1.5-coral-arm64.apk.sha256)
- [Debug APK](https://github.com/Kandoraq/miauterm/releases/download/v0.1.5-coral/Miauterm-arm64-debug.apk)

This version uses the new Android application ID `salu.Kandoraq.Miauterm`. It installs separately from earlier Miauterm releases; their private data is not migrated.

The [Releases page](https://github.com/Kandoraq/miauterm/releases) contains the current APKs and their checksums. The [Build APK workflow](https://github.com/Kandoraq/miauterm/actions/workflows/build-apk.yml) validates the Debian rootfs, builds the ARM64 port mapping library and APK, and verifies the release package before publishing it. Use the Settings update check to receive a notification when GitHub has a newer release; it opens the release in your browser so the APK can be downloaded manually.

### Release history

The [release notes directory](.github/release-notes/) contains changes for [0.1.0](.github/release-notes/v0.1.0.md), [0.1.1](.github/release-notes/v0.1.1.md), [0.1.2-coral](.github/release-notes/v0.1.2-coral.md), [0.1.3-coral](.github/release-notes/v0.1.3-coral.md), [0.1.4-coral](.github/release-notes/v0.1.4-coral.md), and [0.1.5-coral](.github/release-notes/v0.1.5-coral.md).

## Terminal commands and fonts

Show the available app commands from a Debian shell:

```sh
miauterm-cmd --help
```

Post a notification from a Debian shell. The text is shown as the notification message; Android must allow notifications for Miauterm:

```sh
miauterm-cmd run notification "hello from Miauterm"
```

For a custom font, place a readable TTF file at `~/.config/miauterm/font.ttf` in the selected Debian user's home, select **Custom Font** in Settings, then run this in the active terminal:

```sh
miauterm-cmd run reloadfont
```

The app command service must be active for `miauterm-cmd` commands to reach Android. The command list and arguments are also documented in the bundled [`miauterm-cmd` script](app/src/main/assets/miauterm-cmd).

## Build from source

GitHub Actions runs tests, Android lint, and the ARM64 debug build on every push to `main`. A manual workflow run with a release tag also builds and signs the release APK, verifies the package, and publishes the APK and checksums. The workflow uses Java 17, Gradle 8.7, Android SDK platform 34, NDK 26.1.10909125, and CMake 3.22.1.

Install Java 17, Android SDK platform 34, NDK 26.1.10909125, CMake 3.22.1, and an ARM64 glibc cross compiler. Build the guest-only port mapping library before assembling the APK:

```sh
sudo apt-get install gcc-aarch64-linux-gnu
bash scripts/build-port-remap.sh
./gradlew :app:assembleDebug
```

The CI workflow also runs `scripts/verify-rootfs.sh`. That check needs Docker with ARM64 emulation available and validates commands in the bundled Debian archive.

The debug APK is written under `app/build/outputs/apk/debug/`. To build a signed release locally, set `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`. Keep the keystore and passwords outside the repository.

To inspect CI build results, open [Actions](https://github.com/Kandoraq/miauterm/actions/workflows/build-apk.yml). The debug job builds the guest port mapping library, validates the bundled Debian root filesystem, runs tests and lint, then packages the ARM64 APK. The release job verifies the APK signature and publishes a SHA-256 checksum with the APK.

## Project structure

This tree lists every tracked repository file. Generated build output and local working notes are excluded.

```text
miauterm/
├── .github/
│   ├── release-notes/
│   │   ├── v0.1.0.md
│   │   ├── v0.1.1.md
│   │   ├── v0.1.2-coral.md
│   │   ├── v0.1.3-coral.md
│   │   ├── v0.1.4-coral.md
│   │   └── v0.1.5-coral.md
│   ├── workflows/
│   │   └── build-apk.yml
│   └── dependabot.yml
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── assets/
│   │   │   │   ├── fonts/
│   │   │   │   │   ├── FiraCode.ttf
│   │   │   │   │   └── JetBrainsMono.ttf
│   │   │   │   ├── licenses/
│   │   │   │   │   ├── ANDROID-SHMEM-COPYRIGHT.txt
│   │   │   │   │   ├── OFL-1.1.txt
│   │   │   │   │   ├── PROOT-COPYING.txt
│   │   │   │   │   ├── TALLOC-COPYING.txt
│   │   │   │   │   └── THIRD_PARTY_NOTICES.md
│   │   │   │   ├── proot/
│   │   │   │   │   ├── arm64-v8a/
│   │   │   │   │   │   ├── libandroid-shmem.so
│   │   │   │   │   │   ├── libtalloc.so.2
│   │   │   │   │   │   ├── loader
│   │   │   │   │   │   └── proot
│   │   │   │   │   ├── ANDROID-SHMEM-COPYRIGHT.txt
│   │   │   │   │   ├── PROOT-COPYING.txt
│   │   │   │   │   └── TALLOC-COPYING.txt
│   │   │   │   ├── debian-arm64.tar.zst
│   │   │   │   └── miauterm-cmd
│   │   │   ├── cpp/
│   │   │   │   └── miauterm-portmap.c
│   │   │   ├── java/
│   │   │   │   └── salu/
│   │   │   │       └── Kandoraq/
│   │   │   │           └── Miauterm/
│   │   │   │               ├── api/
│   │   │   │               │   ├── ApiCommands.kt
│   │   │   │               │   ├── ApiProtocol.kt
│   │   │   │               │   └── ApiServer.kt
│   │   │   │               ├── runtime/
│   │   │   │               │   ├── DebianBackup.kt
│   │   │   │               │   ├── DebianOperationRunner.kt
│   │   │   │               │   ├── DebianRuntime.kt
│   │   │   │               │   ├── ElfLinkerPatcher.kt
│   │   │   │               │   ├── Environment.kt
│   │   │   │               │   ├── FileInstaller.kt
│   │   │   │               │   ├── MiautermCommandInstaller.kt
│   │   │   │               │   └── SafeDelete.kt
│   │   │   │               ├── service/
│   │   │   │               │   ├── LocalServers.kt
│   │   │   │               │   ├── MiautermService.kt
│   │   │   │               │   └── ServerPorts.kt
│   │   │   │               ├── session/
│   │   │   │               │   ├── CommandNotifications.kt
│   │   │   │               │   ├── SessionAdapter.kt
│   │   │   │               │   ├── SessionManager.kt
│   │   │   │               │   ├── SessionPrompt.kt
│   │   │   │               │   ├── SessionStore.kt
│   │   │   │               │   ├── TerminalBridge.kt
│   │   │   │               │   └── TerminalSession.kt
│   │   │   │               ├── view/
│   │   │   │               │   ├── Chrome.kt
│   │   │   │               │   ├── ChromeDialog.kt
│   │   │   │               │   ├── ExtraKeysConfig.kt
│   │   │   │               │   ├── ExtraKeysLayout.kt
│   │   │   │               │   ├── ExtraKeysView.kt
│   │   │   │               │   ├── SessionTabsView.kt
│   │   │   │               │   └── TerminalView.kt
│   │   │   │               ├── BootstrapDialog.kt
│   │   │   │               ├── BugReport.kt
│   │   │   │               ├── DebianFilesActivity.kt
│   │   │   │               ├── GitHubUpdateChecker.kt
│   │   │   │               ├── MainActivity.kt
│   │   │   │               ├── MiautermApp.kt
│   │   │   │               ├── SettingsActivity.kt
│   │   │   │               └── SettingsStyle.kt
│   │   │   ├── res/
│   │   │   │   ├── drawable/
│   │   │   │   │   ├── badge_red_dot.xml
│   │   │   │   │   ├── drawer_background.xml
│   │   │   │   │   ├── ic_add.xml
│   │   │   │   │   ├── ic_arrow_back.xml
│   │   │   │   │   ├── ic_backup.xml
│   │   │   │   │   ├── ic_bug_report.xml
│   │   │   │   │   ├── ic_build.xml
│   │   │   │   │   ├── ic_clipboard.xml
│   │   │   │   │   ├── ic_delete.xml
│   │   │   │   │   ├── ic_file.xml
│   │   │   │   │   ├── ic_folder.xml
│   │   │   │   │   ├── ic_folder_shared.xml
│   │   │   │   │   ├── ic_history.xml
│   │   │   │   │   ├── ic_info.xml
│   │   │   │   │   ├── ic_keyboard.xml
│   │   │   │   │   ├── ic_launcher_background.xml
│   │   │   │   │   ├── ic_launcher_foreground.xml
│   │   │   │   │   ├── ic_launcher_monochrome.xml
│   │   │   │   │   ├── ic_layers.xml
│   │   │   │   │   ├── ic_lightbulb.xml
│   │   │   │   │   ├── ic_more_vert.xml
│   │   │   │   │   ├── ic_notification.xml
│   │   │   │   │   ├── ic_notifications.xml
│   │   │   │   │   ├── ic_person.xml
│   │   │   │   │   ├── ic_receipt.xml
│   │   │   │   │   ├── ic_restart.xml
│   │   │   │   │   ├── ic_restore.xml
│   │   │   │   │   ├── ic_schedule.xml
│   │   │   │   │   ├── ic_server.xml
│   │   │   │   │   ├── ic_settings.xml
│   │   │   │   │   ├── ic_shield.xml
│   │   │   │   │   ├── ic_text_fields.xml
│   │   │   │   │   ├── ic_tune.xml
│   │   │   │   │   ├── ic_update.xml
│   │   │   │   │   └── root_badge_bg.xml
│   │   │   │   ├── drawable-nodpi/
│   │   │   │   │   └── miauterm_logo.png
│   │   │   │   ├── layout/
│   │   │   │   │   ├── activity_debian_files.xml
│   │   │   │   │   ├── activity_main.xml
│   │   │   │   │   ├── activity_settings.xml
│   │   │   │   │   ├── dialog_bootstrap.xml
│   │   │   │   │   ├── item_debian_file.xml
│   │   │   │   │   └── item_session.xml
│   │   │   │   ├── mipmap-anydpi/
│   │   │   │   │   ├── ic_launcher.xml
│   │   │   │   │   └── ic_launcher_round.xml
│   │   │   │   ├── mipmap-anydpi-v26/
│   │   │   │   │   ├── ic_launcher.xml
│   │   │   │   │   └── ic_launcher_round.xml
│   │   │   │   ├── values/
│   │   │   │   │   ├── arrays.xml
│   │   │   │   │   ├── colors.xml
│   │   │   │   │   ├── strings.xml
│   │   │   │   │   └── themes.xml
│   │   │   │   └── xml/
│   │   │   │       ├── file_paths.xml
│   │   │   │       ├── locales_config.xml
│   │   │   │       ├── root_preferences.xml
│   │   │   │       └── shortcuts.xml
│   │   │   └── AndroidManifest.xml
│   │   └── test/
│   │       └── java/
│   │           └── salu/
│   │               └── Kandoraq/
│   │                   └── Miauterm/
│   │                       ├── api/
│   │                       │   ├── ApiCommandsTest.kt
│   │                       │   ├── ApiProtocolTest.kt
│   │                       │   └── ApiServerStartupTest.kt
│   │                       ├── runtime/
│   │                       │   ├── Api24CompatibilityTest.kt
│   │                       │   ├── DebianBackupSecurityTest.kt
│   │                       │   ├── DebianRuntimeDnsTest.kt
│   │                       │   ├── DebianRuntimeRootfsTest.kt
│   │                       │   ├── ElfLinkerPatcherTest.kt
│   │                       │   ├── FileInstallerTest.kt
│   │                       │   ├── MiautermCommandInstallerTest.kt
│   │                       │   ├── ShadowJavaFilesOs.java
│   │                       │   └── SharedFolderAccessTest.kt
│   │                       ├── service/
│   │                       │   └── ServerPortsTest.kt
│   │                       ├── session/
│   │                       │   ├── CommandNotificationsTest.kt
│   │                       │   ├── SessionPromptTest.kt
│   │                       │   ├── SessionStoreTest.kt
│   │                       │   ├── TerminalBridgeClipboardTest.kt
│   │                       │   └── TerminalSessionTest.kt
│   │                       ├── view/
│   │                       │   ├── ActionPopupPositionTest.kt
│   │                       │   ├── ChromeTest.kt
│   │                       │   ├── ExtraKeysConfigTest.kt
│   │                       │   ├── ExtraKeysLayoutTest.kt
│   │                       │   ├── ExtraKeysViewTest.kt
│   │                       │   ├── PinchTextSizeTest.kt
│   │                       │   └── TerminalInterfaceTest.kt
│   │                       └── BugReportTest.kt
│   ├── build.gradle.kts
│   ├── lint.xml
│   └── proguard-rules.pro
├── core/
│   └── terminal-engine/
│       ├── src/
│       │   ├── main/
│       │   │   ├── cpp/
│       │   │   │   ├── zstd/
│       │   │   │   │   ├── common/
│       │   │   │   │   │   ├── allocations.h
│       │   │   │   │   │   ├── bits.h
│       │   │   │   │   │   ├── bitstream.h
│       │   │   │   │   │   ├── compiler.h
│       │   │   │   │   │   ├── cpu.h
│       │   │   │   │   │   ├── debug.c
│       │   │   │   │   │   ├── debug.h
│       │   │   │   │   │   ├── entropy_common.c
│       │   │   │   │   │   ├── error_private.c
│       │   │   │   │   │   ├── error_private.h
│       │   │   │   │   │   ├── fse.h
│       │   │   │   │   │   ├── fse_decompress.c
│       │   │   │   │   │   ├── huf.h
│       │   │   │   │   │   ├── mem.h
│       │   │   │   │   │   ├── portability_macros.h
│       │   │   │   │   │   ├── xxhash.h
│       │   │   │   │   │   ├── zstd_common.c
│       │   │   │   │   │   ├── zstd_deps.h
│       │   │   │   │   │   ├── zstd_internal.h
│       │   │   │   │   │   └── zstd_trace.h
│       │   │   │   │   ├── decompress/
│       │   │   │   │   │   ├── huf_decompress.c
│       │   │   │   │   │   ├── zstd_ddict.c
│       │   │   │   │   │   ├── zstd_ddict.h
│       │   │   │   │   │   ├── zstd_decompress.c
│       │   │   │   │   │   ├── zstd_decompress_block.c
│       │   │   │   │   │   ├── zstd_decompress_block.h
│       │   │   │   │   │   └── zstd_decompress_internal.h
│       │   │   │   │   ├── LICENSE
│       │   │   │   │   ├── zstd.h
│       │   │   │   │   ├── zstd_decoder.c
│       │   │   │   │   └── zstd_errors.h
│       │   │   │   ├── CMakeLists.txt
│       │   │   │   └── miauterm-pty.c
│       │   │   └── java/
│       │   │       └── salu/
│       │   │           └── Kandoraq/
│       │   │               └── Miauterm/
│       │   │                   ├── emulator/
│       │   │                   │   ├── Base64Lenient.kt
│       │   │                   │   ├── CharWidth.kt
│       │   │                   │   ├── KeyMapper.kt
│       │   │                   │   ├── TerminalBuffer.kt
│       │   │                   │   ├── TerminalColor.kt
│       │   │                   │   ├── TerminalEmulator.kt
│       │   │                   │   ├── TerminalRow.kt
│       │   │                   │   └── TerminalSearch.kt
│       │   │                   ├── pty/
│       │   │                   │   ├── ProcessReapCoordinator.kt
│       │   │                   │   ├── PtyNative.kt
│       │   │                   │   └── PtyProcess.kt
│       │   │                   └── view/
│       │   │                       └── Sextants.kt
│       │   └── test/
│       │       └── java/
│       │           └── salu/
│       │               └── Kandoraq/
│       │                   └── Miauterm/
│       │                       ├── emulator/
│       │                       │   ├── CharWidthTest.kt
│       │                       │   ├── KeyMapperTest.kt
│       │                       │   ├── TerminalAstralCharTest.kt
│       │                       │   ├── TerminalBufferTest.kt
│       │                       │   ├── TerminalEmulatorTest.kt
│       │                       │   ├── TerminalEmulatorUtf8Test.kt
│       │                       │   ├── TerminalModesTest.kt
│       │                       │   ├── TerminalOscTest.kt
│       │                       │   ├── TerminalReflowTest.kt
│       │                       │   ├── TerminalSchemeChangeTest.kt
│       │                       │   ├── TerminalScrollEquivalenceTest.kt
│       │                       │   ├── TerminalTranscriptTest.kt
│       │                       │   └── TerminalWideCharTest.kt
│       │                       └── pty/
│       │                           └── ProcessReapCoordinatorTest.kt
│       └── build.gradle.kts
├── docs/
│   ├── images/
│   │   └── miauterm-terminal.png
│   └── SECURITY.md
├── gradle/
│   ├── wrapper/
│   │   ├── gradle-wrapper.jar
│   │   └── gradle-wrapper.properties
│   └── libs.versions.toml
├── scripts/
│   ├── build-port-remap.sh
│   ├── package-debian-rootfs.sh
│   ├── sanitize-rootfs-tar.py
│   └── verify-rootfs.sh
├── .gitignore
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── LICENSE
├── README.md
├── settings.gradle.kts
└── THIRD_PARTY_NOTICES.md
```

The `salu.Kandoraq.Miauterm` package and `core/terminal-engine` contain upstream-derived code alongside Miauterm changes. The package names do not imply that Miauterm authored the retained upstream components. The Android application ID is `salu.Kandoraq.Miauterm`; it differs from earlier Miauterm releases and installs as a separate app without migrating their private data.

## Credits

- [Cortex Terminal](https://github.com/ssfuisu/cortex) for the upstream Android terminal application and terminal engine code used as the starting point for this fork.
- [PRoot](https://github.com/proot-me/proot) for the user-space runtime used to launch Debian.
- [Debian](https://www.debian.org/) for Debian 13 and its ARM64 userspace.
- [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) and [Fira Code](https://github.com/tonsky/FiraCode) for bundled fonts.

See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and the files under `app/src/main/assets/licenses/` for the notices supplied with bundled components.
