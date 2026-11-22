# Third-party notices

Miauterm combines independently licensed application code, runtime components, and a Debian ARM64 root filesystem. The Apache License 2.0 in [`LICENSE`](LICENSE) applies to Miauterm and retained Cortex-derived application source unless a file or component carries its own notice. It does not replace the notices below.

## Debian root filesystem

The bundled system is Debian ARM64. Debian is a community distribution; there is no single copyright holder or blanket license for all packages. Copyright and license terms belong to each included package. Package notices remain in `/usr/share/doc/<package>/copyright` inside the root filesystem. Consult those files before redistributing or modifying Debian packages.

- Debian legal information: <https://www.debian.org/legal/licenses/>
- Debian disclaimer and trademark information: <https://www.debian.org/legal/disclaimer/>
- Debian Policy on package copyright files: <https://www.debian.org/doc/debian-policy/ch-archive>

## PRoot runtime

PRoot is distributed under GNU GPL version 2 or later. The bundled PRoot copyright and license text is in [`PROOT-COPYING.txt`](PROOT-COPYING.txt). Bundled talloc carries its separate GPLv3 notice in [`TALLOC-COPYING.txt`](TALLOC-COPYING.txt). Android shared-memory support carries the attribution in [`ANDROID-SHMEM-COPYRIGHT.txt`](ANDROID-SHMEM-COPYRIGHT.txt).

- PRoot source: <https://github.com/proot-me/proot>
- PRoot static runtime source: <https://github.com/proot-me/proot-static-build>

## zstd

The native decoder under `core/terminal-engine/src/main/cpp/zstd/` comes from the [zstd project](https://github.com/facebook/zstd) and is distributed under the BSD 3-Clause license. The full license text is included in [`zstd-LICENSE.txt`](zstd-LICENSE.txt).

## Fonts

- **Fira Code** — Copyright 2014–2020 The Fira Code Project Authors; SIL Open Font License 1.1. The license is included in [`OFL-1.1.txt`](OFL-1.1.txt). Source: <https://github.com/tonsky/FiraCode>.
- **JetBrains Mono** — Copyright 2020 The JetBrains Mono Project Authors; SIL Open Font License 1.1. The license is included in [`OFL-1.1.txt`](OFL-1.1.txt). Source: <https://github.com/JetBrains/JetBrainsMono>.

## Project credits

Miauterm is an unofficial fork of [Cortex Terminal](https://github.com/ssfuisu/cortex). The retained upstream source is distributed under Apache License 2.0. Third-party names and marks remain the property of their owners; these notices do not grant trademark rights.
