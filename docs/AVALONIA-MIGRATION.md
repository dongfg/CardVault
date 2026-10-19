# Avalonia 迁移

分支：`codex/avalonia-rewrite`。应用入口为 `src/CardVault`，工程已完整替换为 Avalonia；旧 Kotlin 模块、Gradle 工程、旧截图及发布流程已移除，可通过 Git 历史查看。确认效果后再合并；本轮不发布、不生成安装包。

## 开发

需要 .NET 10 SDK。Windows 和 macOS 使用同一套源码：

```sh
dotnet build src/CardVault -c Release
dotnet run --project tests/CardVault.Checks
dotnet run --project src/CardVault -- --demo
```

演示模式使用 `build/avalonia-demo/cardvault.db`，与旧版演示和正常卡册隔离。仅当数据库不存在时创建虚构的 SAMPLE 卡片。

正常运行默认使用进程文件同级的 `cardvault.db`；macOS 若在 `.app` 中运行，则使用 `.app` 外同级的数据库。开发时可显式指定项目根目录的旧卡册：

```powershell
$env:CARDVAULT_DB_PATH = Join-Path $PWD 'cardvault.db'
dotnet run --project src/CardVault
```

```sh
CARDVAULT_DB_PATH="$PWD/cardvault.db" dotnet run --project src/CardVault
```

环境变量优先于 demo 路径；若要独立演示，先清除该变量。

## 数据兼容

`application_id=0x43564C54`、`user_version=1`，`cards`、`images`、`metadata` 表定义不变。图片引用继续使用小写 SHA-256，原始照片以 BLOB 保存，正反面相同图片只存一次。不自动迁移或覆盖旧数据库。

继续使用 DELETE 日志模式、FULL 同步、外键、IMMEDIATE 写事务、revision 过期写入检查和保存前 `VACUUM INTO` 快照。只清理本应用命名的备份并保留最近五份。连接池关闭，每次读写后释放连接，适合原有 OneDrive 轮流使用方式；应用不合并同步冲突。数据库仍未加密。

存储检查覆盖原 SQL 格式与 BLOB 引用、图片去重与独立读取、失败回滚、旧 revision 拒绝、备份保留、数据库完整性、连接释放、搜索、短卡号掩码以及 Windows/macOS 路径规则。测试只使用临时目录。

## Native AOT 约束

- `net10.0`，`PublishAot=true`，`IsAotCompatible=true`，将编译和 AOT/裁剪分析警告作为错误。
- XAML 仅用于编译后的全局样式；页面使用强类型 C# 控件和事件。没有反射绑定、运行时 XAML 加载、自定义反射序列化或动态代码生成。
- Microsoft.Data.Sqlite 直接执行参数化 SQL，无 ORM；图片使用 Avalonia/SkiaSharp 解码，文件选择和剪贴板使用跨平台接口。
- 标准 `dotnet build` / `dotnet run` 是开发编译与预览，不执行 AOT 发布或分发打包。
- 本轮仅完成 Windows 的实际运行和编译检查。尚未执行 Native AOT 链接，也未在 macOS 实机验证；AOT 发布需后续在对应系统安装原生工具链后验证。不能把无分析警告等同于已验证 AOT 产物。

后续授权打包时再处理 Windows 原生工具链、macOS 应用包/签名、公证及 CI 发布流程。当前 `.github/workflows/check-avalonia.yml` 仅在 Windows/macOS 上编译与运行存储检查，不打包、不发布。原有图标已迁移到 `src/CardVault/Assets`，源图与导出工具位于 `design/icons`，本轮未重新生成图标。

## 工程结构

应用的 `Views`、`Controls`、`Models`、`Storage`、`Imaging`、`Demo` 分别存放窗口、控件、模型、数据库、图片处理和演示代码，使用对应的 C# 命名空间。`App.axaml.cs` 管理应用生命周期，`Program.cs` 仅保留启动入口。测试项目位于 `tests`，UI 设计资料位于 `design`，技术说明位于 `docs`。
