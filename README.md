# CardVault

用 Kotlin Multiplatform + Compose Multiplatform 编写的卡片管理应用，当前运行目标为 Windows / macOS / Linux 桌面（JVM）。界面采用竖屏布局和自绘标题栏。

## 运行

```powershell
.\gradlew.bat :desktopApp:run
```

默认从空卡册开始。数据保存在项目根目录的 `data/`，包括 `cards.properties` 和 `images/`。图片保存时复制进该目录，移动原始照片不会影响已保存记录。当前采用本地文件存储，未实现 SQLite、加密或云同步。

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
| macOS | `./gradlew :desktopApp:packageDmg` | `desktopApp/build/compose/binaries/main/dmg/` |
| Linux | `./gradlew :desktopApp:packageDeb` | `desktopApp/build/compose/binaries/main/deb/` |

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
