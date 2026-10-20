# CardVault

使用 **C# / .NET 10 / Avalonia** 编写的卡片管理应用，面向 Windows 和 macOS。延续竖屏卡册、卡面大图、浅色背景、绿色强调色和自绘标题栏，兼容原 Kotlin 版 SQLite 卡册。

当前重写在 `codex/avalonia-rewrite` 分支，待界面确认后合并。本轮仅进行源码开发、编译和运行预览，没有生成分发包。

## Tag 发布

推送 `v主版本.次版本.补丁号` tag 会触发 `.github/workflows/build-desktop.yml`，在对应系统上执行检查、Native AOT 发布和打包。Windows x64 和 macOS ARM64 全部成功后自动创建 GitHub Release，不覆盖已有 Release。

| 平台 | Release 文件 |
| --- | --- |
| Windows x64 | `CardVault-windows-x64.exe`（单文件，直接运行） |
| macOS Apple Silicon | `CardVault-macos-arm64.dmg` |

```sh
git tag v1.0.1
git push origin v1.0.1
```

版本号取自 tag；产物同时上传到 Actions，保留 30 天。macOS 包最低系统版本为 15，仅做 ad-hoc 签名，尚未配置 Developer ID 签名与公证。请先将 `.app` 从 DMG 拖出再使用卡册。

分发包排除 `.pdb`、`.dbg`、`.dwarf` 和 macOS `.dSYM` 调试符号，保留必需的原生库。Native AOT 的精简运行时已编入主程序，不需要另外安装 .NET。

Windows 单文件 EXE 使用原生 C++ 启动器，内嵌 LZX 压缩的 AOT 主程序及依赖，启动时释放到 `%LOCALAPPDATA%/CardVault/cache/<内容哈希>`，复用并校验缓存，无需管理员权限。`cardvault.db` 和 `backups` 仍位于外层 EXE 同级；`CARDVAULT_DB_PATH` 路径覆盖与 `--demo` 独立演示继续生效。构建需要 Visual Studio x64 C++ 工具链，CI 使用已预装工具链的 Windows runner；上传前检查首次释放、缓存复用及含中文/空格路径的启动器运行。

普通 PR 检查由 `check-avalonia.yml` 执行，仅编译与运行存储检查，不打包。本次单文件发布配置尚未在远端运行；本地没有创建或推送 tag。

Windows 个人 CA、根证书与无密码签名 PFX 的生成脚本及参数见 [Windows 签名说明](docs/WINDOWS-SIGNING.md)。证书下载地址确定后再接入 CI。

## 运行

安装 .NET 10 SDK，在项目根目录执行：

```sh
dotnet build CardVault.slnx -c Release
dotnet run --project tests/CardVault.Checks -c Release
dotnet run --project src/CardVault -- --demo
```

演示模式创建独立的 `build/avalonia-demo/cardvault.db`，包含三张虚构 SAMPLE 卡片，支持完整增删改查。已有演示数据不会重复初始化。

正常运行：

```sh
dotnet run --project src/CardVault
```

默认读取可执行文件同级的 `cardvault.db`；macOS 应用包运行时读取 `.app` 同级的数据库。首次使用点击「创建新卡册」，已有卡册未同步时点击「重新读取」。

开发时读取项目根目录现有卡册，可显式设置路径：

```powershell
$env:CARDVAULT_DB_PATH = Join-Path $PWD 'cardvault.db'
dotnet run --project src/CardVault
```

macOS：`CARDVAULT_DB_PATH="$PWD/cardvault.db" dotnet run --project src/CardVault`。

该环境变量也优先于演示路径；运行独立演示前请清除它。

## 功能与数据

- 图片优先的卡面列表；搜索名称、卡号和备注。
- 添加、查看、编辑、删除；正反面照片导入、替换和移除。
- 卡号默认掩码，可显示、隐藏和复制。
- 正反面切换、大图缩放和拖动；未保存修改离开提示。
- 保留原 `cards`、`images`、`metadata` 表和数据库版本；图片以 SHA-256 去重并保存为 BLOB。
- 保存前备份，保留最近五份；事务回滚和 revision 检查避免过期写入。
- DELETE 日志模式，关闭连接池，每次读写后释放连接。

数据库尚未加密。通过 OneDrive 切换设备时，同一时间只在一台设备使用，关闭应用并等同步完成后再换设备。应用不合并同步冲突。

恢复备份时关闭应用，保留当前数据库，再将 `backups/cardvault-*.db` 复制为 `cardvault.db`。

## Native AOT 与平台验证

项目已启用 Native AOT 配置及兼容性分析，使用编译 XAML、强类型页面代码和直接 SQL，避免反射绑定及运行时代码生成。开发预览仍使用普通 .NET 构建。

当前已在 Windows 实际运行验证；macOS 窗口、文件选择、剪贴板及 Native AOT 链接仍需在对应系统验证。本轮未执行 `dotnet publish` 或任何分发打包任务。

完整说明见 [Avalonia 迁移记录](docs/AVALONIA-MIGRATION.md)。项目已完整切换到 Avalonia；旧实现可通过 Git 历史查看。

## 结构

```text
CardVault/
├── CardVault.slnx
├── global.json
├── src/CardVault/
│   ├── App.axaml / App.axaml.cs
│   ├── Program.cs
│   ├── Views/                 # 窗口与页面
│   ├── Controls/              # 卡面控件
│   ├── Models/                # 卡片模型
│   ├── Storage/               # SQLite 存储
│   ├── Imaging/               # 图片校验与解码
│   ├── Demo/                  # 独立演示数据
│   └── Assets/                # 应用图标
├── tests/CardVault.Checks/    # 存储兼容性与数据保护检查
├── design/                   # UI 设计与图标源文件
├── docs/                     # 技术说明
├── scripts/                  # CI 打包脚本
└── packaging/                # 平台打包元数据
```

[UI 设计依据](design/UI-DESIGN.md)延续原有视觉设计。构建输出、演示卡册和本地缓存均不纳入 Git。

## 开源协议

[MIT License](LICENSE)，Copyright © 2026 dongfg。
