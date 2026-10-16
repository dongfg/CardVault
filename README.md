# CardVault

用 Kotlin Multiplatform + Compose Multiplatform 编写的卡片管理应用，当前运行目标为 Windows / macOS / Linux 桌面（JVM）。界面采用竖屏布局和自绘标题栏。

## 界面预览

以下为 Windows 下 demo 模式的实际截图，使用虚构卡片数据。

| 卡片列表 | 卡片详情 | 编辑卡片 |
| :---: | :---: | :---: |
| <img src="docs/screenshots/list.jpg" alt="卡片列表：卡面图片及叠加名称" width="250"> | <img src="docs/screenshots/detail.jpg" alt="卡片详情：正反面、卡号和备注" width="250"> | <img src="docs/screenshots/edit.jpg" alt="编辑卡片：照片、名称、卡号和备注表单" width="250"> |

## 下载

| 平台 | 下载 |
| --- | --- |
| Windows x64 | [单文件 EXE](https://github.com/dongfg/CardVault/releases/latest/download/CardVault-windows-x64.exe) |
| macOS Intel | [DMG](https://github.com/dongfg/CardVault/releases/latest/download/CardVault-macos-x64.dmg) |
| macOS Apple Silicon | [DMG](https://github.com/dongfg/CardVault/releases/latest/download/CardVault-macos-arm64.dmg) |

[所有版本](https://github.com/dongfg/CardVault/releases) · [手动构建与产物](https://github.com/dongfg/CardVault/actions/workflows/build-desktop.yml)

下载链接在首次发布 Release 后生效。macOS 产物目前未配置签名与公证。

## 运行

```powershell
.\gradlew.bat :desktopApp:run
```

默认从空卡册开始。数据保存在项目根目录的 `data/`，包括 `cards.properties` 和 `images/`。图片保存时复制进该目录，移动原始照片不会影响已保存记录。当前采用本地文件存储，未实现 SQLite、加密或云同步。

Windows 分发版的数据保存在外层 EXE 同级的 `data/`；macOS 分发版保存在 `~/Library/Application Support/CardVault/data/`。

预览图片列表，可运行独立演示卡册：

```powershell
.\gradlew.bat :desktopApp:run --args="--demo"
```

演示数据位于 `build/demo-data/`，包含三张明确标注 SAMPLE / DEMO 的虚构卡片，与正常卡册隔离。演示模式也支持添加、编辑和删除，修改会保留。

## 打包

在项目根目录、对应操作系统上执行：

| 平台 | 命令 | 输出目录 |
| --- | --- | --- |
| Windows | `.\gradlew.bat :desktopApp:packageSingleExe` | `desktopApp/build/distributions/`（`CardVault-<版本>-windows-<架构>-single.exe`） |
| macOS | `bash ./gradlew :desktopApp:packageDmg` | `desktopApp/build/compose/binaries/main/dmg/` |
| Linux | `bash ./gradlew :desktopApp:packageDeb` | `desktopApp/build/compose/binaries/main/deb/` |

GitHub Actions 仅手动触发：打开 **Actions → Build desktop packages → Run workflow**，分别构建 Windows x64、macOS Intel 和 Apple Silicon，产物保留 30 天。勾选 `publish_release` 后，全部构建成功才会发布到 Release；版本取自 `desktopApp/build.gradle.kts` 中的 `appVersion`，已存在的 Release 不会被覆盖。

## 功能

- 卡面图片优先的列表；名称、卡号、备注搜索。
- 添加、查看、编辑、删除；正反面照片分别导入、替换和移除。
- 卡号默认掩码，支持显示、隐藏和复制。
- 图片大图预览、缩放和拖动。
- 保存失败保留草稿；离开或关闭窗口前检查未保存修改。
- 统一自绘窗口栏：拖动、最小化、最大化／还原、关闭。

## 检查

```powershell
.\gradlew.bat :desktopApp:test :shared:jvmTest
```

测试覆盖搜索、短卡号掩码、记录重读、图片独立保存、失败保存保护和删除清理。

## 结构

- `shared/src/commonMain`：可共享的数据模型、界面与存储接口。
- `desktopApp/src/main`：桌面窗口、文件选择、图片读取、本地存储与演示数据。
- `UI-DESIGN.md`：设计与交互约定。

当前只在 Windows 上进行实际运行验证；macOS 与 Linux 的窗口行为仍需在对应平台验证。

## 开源协议

本项目采用 [MIT License](LICENSE)，Copyright © 2026 dongfg。
