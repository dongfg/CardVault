# Windows 个人签名证书

脚本使用 Bash 和 OpenSSL 3+，可在 Linux 服务器、macOS 或 Windows Git Bash 中运行。无需管理员权限，不安装证书、不修改 CI、不签署程序，也不要求设置环境变量。

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

按已选定的在线方案，先上传 `root-ca.cer` 和 `codesign.pfx`，以后把两个固定 HTTPS 地址提供给 CI。CA 私钥无需交给 CI。公开的无密码 PFX 可以被任何下载者用于签名，这是此方案已经接受的取舍。

默认 `signing-material` 目录和证书私钥文件均被 Git 忽略。输出目录必须不存在；再次执行会拒绝覆盖，以免意外换掉已被设备信任的 CA。更新签名证书应复用现有 CA，不能通过清空目录后重跑来续签。

运行成功后，Windows 双击 `root-ca.cer` 可进入安装向导，将它安装到所选用户或计算机的“受信任的根证书颁发机构”。本脚本不会代为安装。该私人信任不保证消除 SmartScreen 或 Smart App Control 拦截。
