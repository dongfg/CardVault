# Windows 个人签名证书

生成脚本使用 Bash 和 OpenSSL 3+，可在 Linux 服务器、macOS 或 Windows Git Bash 中运行。生成过程无需管理员权限，不安装证书、不签署程序，也不要求设置环境变量。

## 在 Windows 上安装

只需要下载并安装 [root-ca.cer](https://cdn.dongfg.com/certs/root-ca.cer)，不需要安装 PFX、签名私钥或 CA 私钥。

1. 双击 `root-ca.cer`，确认名称是 `Code Signing Root CA`，选择“安装证书”。
2. 仅自己使用，选择“当前用户”；需要全机用户都信任时，选择“本地计算机”（需要管理员权限）。
3. 选择“将所有的证书都放入下列存储”，浏览并选择“受信任的根证书颁发机构”。
4. 完成安装，按系统提示确认信任。

当前根证书的 SHA-256 指纹：

```text
5E:19:6B:56:43:2C:81:6D:7D:53:63:8B:0B:F5:EF:87:E5:0B:32:11:C5:CD:32:5D:F4:E2:66:43:9D:9A:FB:96
```

签名者为 `dongfg`，当前签名证书有效期至 2029-10-19 UTC。证书信息也可查看 [certificate-info.txt](https://cdn.dongfg.com/certs/certificate-info.txt)。私人根证书信任不保证消除 SmartScreen 或 Smart App Control 提示。

## CI 自动签名

Windows tag 发布已接入以下固定地址，无需配置 Secrets、证书密码或自定义环境变量：

- 根证书：`https://cdn.dongfg.com/certs/root-ca.cer`
- 无密码 PFX：`https://cdn.dongfg.com/certs/codesign.pfx`
- RFC 3161 时间戳服务：`http://timestamp.digicert.com`

`scripts/windows-signing.ps1` 自动查找 Windows SDK 的 x64 SignTool，下载并检查根证书指纹、PFX 私钥、代码签名用途和有效期。打包顺序为：

1. 签署发布目录中的 AOT 主程序及 DLL，使用 SHA-256 和时间戳。
2. 验证签名身份和时间戳；再计算文件哈希并封装到单文件启动器。
3. 签署并验证外层 `CardVault-windows-x64.exe`，再执行启动器释放检查和上传。

签名或验证失败即终止发布，不回退为未签名文件。根证书仅在临时 GitHub Actions runner 的当前用户根证书库中加入信任，完成后移除本次添加的信任和下载文件；本地运行不会自动安装根证书。更换签名证书且 CA 不变时，替换远端 PFX 即可；更换 CA 时，还需要更新脚本中的根指纹并重新在设备上安装信任。

发布日志使用 UTC 时间，按阶段输出开始、结束、耗时和进程退出码，运行超过 10 秒会定期输出状态。证书下载另外报告 HTTP 状态、DNS/TCP/TLS、首字节和总耗时。每次下载连接超时 10 秒、总超时 40 秒，最多两次；签名及时间戳每次最多 90 秒、最多三次；验签和 Authenticode 策略检查各 60 秒；压缩 300 秒；资源编译 60 秒；C++ 编译 180 秒；启动器自检查各 120 秒。超时终止相应进程树，Windows 打包步骤另有 20 分钟总上限。失败原因同时写入 Actions 注解，便于区分 CDN、时间戳服务、证书校验或本地打包阶段。

macOS 不使用这套 Windows 私人签名证书，维持现有 ARM64 DMG 和 ad-hoc 签名流程。

## 生成证书

```bash
bash scripts/create-signing-certificates.sh \
  --ca-name "Dongfg Personal Code Signing Root CA" \
  --publisher "dongfg" \
  --output ./signing-material
```

若在 Windows 中运行，打开 Git Bash 后进入项目目录执行；Git for Windows 自带的 OpenSSL 可直接使用。也可以复制这个脚本到服务器单独运行，不依赖项目其他文件。

## 身份信息

| 参数 | 证书字段 | 含义 |
| --- | --- | --- |
| `--ca-name` | 根证书 Subject CN | CA 名称，如 `Dongfg Personal Code Signing Root CA` |
| `--publisher` | 签名证书 Subject CN | 签名者名称，例如个人姓名、昵称或发布身份 |
| `--organization` | Subject O，可选 | 组织名称；个人使用可以省略，不需要虚构公司 |
| `--country` | Subject C，可选 | 两位大写国家代码，如 `CN` |

Subject 是“证书属于谁”；Issuer 是“谁签发了这张证书”。根证书自签，因此它的 Subject 和 Issuer 相同。代码签名证书由根 CA 签发，其 Issuer 自动来自 CA，不能另填一个不匹配的签发者。

个人自建 CA 不做外部身份审核，这些名称是你自行声明的身份，不代表微软或公共 CA 认证。不是 HTTPS 网站证书，不需要绑定域名；上传地址也不需要写入 Subject 或 SAN。姓名和组织可用中文，脚本按 UTF-8 处理。

可选示例：

```bash
bash scripts/create-signing-certificates.sh \
  --ca-name "Dongfg Personal Code Signing Root CA" \
  --publisher "dongfg" --organization "Personal Projects" --country CN \
  --ca-days 3650 --signing-days 1095 --output ./signing-material
```

## 生成文件

| 文件 | 用途 |
| --- | --- |
| `root-ca.cer` | DER 格式根证书，提供给 Windows 设备安装信任 |
| `codesign.pfx` | 含签名私钥、签名证书和根证书的 PFX，密码为空，供后续 CI 下载签名 |
| `codesign.cer` | DER 格式签名证书，可查看签名者信息，无私钥 |
| `root-ca.pem` / `codesign.pem` | PEM 格式证书，便于服务器和 OpenSSL 使用 |
| `root-ca.key.pem` | CA 私钥，在服务器保留，供以后签发新证书 |
| `codesign.key.pem` | 签名私钥，已包含在 PFX 中 |
| `certificate-info.txt` | Subject、Issuer、有效期、SHA-256 指纹 |
| `*.cnf` / `codesign.csr.pem` | 生成配置和证书请求 |

CA 是身份、私钥和签发能力的组合；“根证书”就是这个 CA 的自签名证书，不需要另外生成第三张“CA 证书”。脚本默认根证书有效 3650 天，签名证书有效 1095 天；根使用 RSA 4096，签名密钥使用 RSA 3072，证书均使用 SHA-256 签署。

根证书设置 `CA:true`、只能直接签发终端证书的 `pathlen:0`，签名证书设置 `CA:false` 和 `codeSigning` 用途。签名私钥与 CA 私钥均无密码。PFX 是**空密码**，不是省略完整性校验；可使用 `-passin pass:` 打开，后续 SignTool 可按空密码方式使用。

CA 私钥无需交给 CI；CI 只下载 `root-ca.cer` 和 `codesign.pfx`。公开的无密码 PFX 可以被任何下载者用于签名，这是此方案已经接受的取舍。

默认 `signing-material` 目录和证书私钥文件均被 Git 忽略。输出目录必须不存在；再次执行会拒绝覆盖，以免意外换掉已被设备信任的 CA。更新签名证书应复用现有 CA，不能通过清空目录后重跑来续签。

运行成功后，Windows 双击 `root-ca.cer` 可进入安装向导，将它安装到所选用户或计算机的“受信任的根证书颁发机构”。本脚本不会代为安装。该私人信任不保证消除 SmartScreen 或 Smart App Control 拦截。
