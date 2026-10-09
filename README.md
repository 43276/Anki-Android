# AnkiDroid（个人 fork）

本仓库 fork 自 [AnkiDroid 官方项目](https://github.com/ankidroid/Anki-Android)，用于维护个人定制修改，非官方发行版。

AnkiDroid 是 Android 上的间隔重复记忆应用。通用功能和使用方法请参考 [上游 README](https://github.com/ankidroid/Anki-Android#readme) 和 [用户手册](https://docs.ankidroid.org/)。

## 定制修改

- 最新版基于[原版v2.25.1](https://github.com/ankidroid/Anki-Android/releases/tag/v2.25.1)修改。
- 「设置 → 显示 → 背景」可选择复习页的「官方默认」或「修改配置」；修改配置在深色模式使用纯黑、浅色模式使用纯白。
- 新学习屏支持复习页全局桌宠。请在「设置 → 新学习屏 → 桌宠」开启功能、调整参数，并导入自己的媒体组；应用不附带素材。
- 目前适配了中英文。

各版本的具体改动以本仓库的提交记录和发布说明为准。

## 安装与反馈

- 本 fork 的安装包（如有）见 [Releases](https://github.com/43276/Anki-Android/releases)。
- 安装说明、版本差异和签名证书指纹以本 fork 的发布说明为准。
- 定制版本的问题请在本仓库反馈，并注明版本、复现步骤及与上游的差异。

## 本地构建

使用 Android Studio 打开仓库，安装构建脚本要求的 Android SDK，并按 [Gradle JVM 配置](gradle/gradle-daemon-jvm.properties) 设置 JDK。更多环境说明见 [上游开发指南](https://github.com/ankidroid/Anki-Android/wiki/Development-Guide) 和 [本地 JVM 说明](docs/development/gradle-daemon-jvm.md)。

在仓库根目录构建用于本地调试的 `fullDebug` APK：

```powershell
# Windows / PowerShell
.\gradlew.bat :AnkiDroid:assembleFullDebug
```

```sh
# Linux / macOS
./gradlew :AnkiDroid:assembleFullDebug
```

产物位于 `AnkiDroid/build/outputs/apk/full/debug/`。公开发行时应单独配置应用名称、包名和自己的 release 签名。

## 许可证

本 fork 延续上游的许可证及版权声明，各文件和依赖以其自身声明为准：

- AnkiDroid 主项目：[GPL-3.0-or-later](COPYING)。
- 部分 Anki 后端代码：[AGPL-3.0-or-later](https://github.com/ankitects/anki/blob/main/LICENSE)；后端构建项目见 [Anki-Android-Backend](https://github.com/ankidroid/Anki-Android-Backend)。
- AnkiDroid API：[LGPL-3.0-or-later](api/COPYING.LESSER)。
- 第三方依赖：保留各自的许可证、版权和归属声明，参见 [上游许可证清单](https://github.com/ankidroid/Anki-Android/wiki/Licences)。

发布修改后的 APK 时，需按适用许可证提供与该 APK 对应的完整源码和必要的构建脚本，包括修改及所用后端的对应源码。建议每次发布使用独立 tag，并在发布页注明源码入口、构建方法、修改内容和日期。

## Credits
<!-- Do not rename this section. AnkiDroid contains a deep link to this heading.
See https://github.com/ankidroid/Anki-Android/pull/11803. -->

感谢 AnkiDroid、Anki 的原作者、代码贡献者、翻译者及第三方依赖作者。

- [AnkiDroid 贡献者](https://github.com/ankidroid/Anki-Android/graphs/contributors)
- [Anki 项目](https://github.com/ankitects/anki)
- [AnkiDroid 翻译项目](https://crowdin.com/project/ankidroid)
