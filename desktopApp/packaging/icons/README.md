# CardVault 图标

统一使用深绿色底、叠放卡片的原创图标，无文字。

- `cardvault-source.png`：imagegen 生成的透明背景原图。
- `cardvault.ico`：Windows 应用及单文件启动器，含 16–256 像素版本。
- `cardvault.icns`：macOS 应用图标，含高清和 Retina 版本。
- `../../src/main/resources/icons/cardvault.png`：窗口及 Linux 图标，256 像素。

安装 Pillow 后，在项目根目录运行 `python desktopApp/packaging/icons/generate_icons.py`，从原图重新导出各平台格式。

生成提示：深森林绿圆角方形底，两张叠放的横向卡片；后卡薄荷绿，前卡象牙白，前卡包含深绿磁条和短横线；正视、简洁几何、大留白、小尺寸可辨识，无文字、数字、水印或锁形，外部背景透明。
