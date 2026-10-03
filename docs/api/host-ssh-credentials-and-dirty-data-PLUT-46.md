# 主机 SSH 凭据加解密说明与历史脏数据处置（PLUT-46）

## 契约（修复后）

SSH 凭据（`host_info.ssh_private_key` / `ssh_password`）的加解密收敛为「写一次、读一次」：

- **写侧（唯一加密点）**：`HostServiceImpl.createHost / updateHost`。控制器层不再预加密；
  服务层对明文加密一次；若传入值已是密文（`AesUtil.isEncrypted` 判定）则原样落库，不二次加密。
- **读侧（唯一解密点）**：`DeploymentAccess.credentials`。单次 `AesUtil.decrypt` 还原明文供 SSH 连接。
- 加密算法：AES（Hutool `SecureUtil.aes`，默认 key `GamePlatform2024`，Base64 存储）。

DTO 的 API 契约：请求体中的 `sshPrivateKey` / `sshPassword` 为**明文**（UI 表单原样透传）；
后端对已加密输入保持幂等，但请勿把密文当明文传入。

## 历史脏数据

修复前（commit `3c5df3e` 及之前）存在「加密两次、解密一次」：`HostController.create` 预加密 +
`HostServiceImpl.createHost` 再加密，导致经 UI/API 密钥认证纳管的主机私钥为**双重加密**，
读侧单次解密得到密文，SSH 密钥解析必然失败（日志「未解析到任何密钥对」）。

双重加密落库的数据**无法在修复后自动解开**（写进兼容方案的「自动解脏」已被否决），
处置方式为由管理员**重新录入私钥**。

### 受影响主机清单查法

特征：`ssh_private_key` 解密一次后仍不是以 `-----BEGIN` 开头的 PEM 明文。
对 SQLite 部署，在宿主机上执行（示例路径按实际 `docker-compose.yml` 挂载调整）：

```bash
python3 - <<'EOF'
import sqlite3, base64
from Crypto.Cipher import AES  # pip install pycryptodome（或用容器内同版本库）

KEY = b"GamePlatform2024"

def dec_once(s):
    raw = base64.b64decode(s)
    return AES.new(KEY, AES.MODE_ECB).decrypt(raw).rstrip(b"\x00\x01\x02\x03\x04\x05\x06\x07\x08").decode("utf-8", "ignore")

conn = sqlite3.connect("/path/to/data/game_platform.db")
for hid, ip, name, k in conn.execute(
        "SELECT id, ip_address, host_name, ssh_private_key FROM host_info "
        "WHERE is_deleted=0 AND ssh_private_key IS NOT NULL AND ssh_private_key != ''"):
    try:
        once = dec_once(k)
        dirty = not once.lstrip().startswith("-----BEGIN")
        print(f"{'DIRTY ' if dirty else 'OK    '} id={hid} ip={ip} name={name}")
    except Exception as e:
        print(f"DIRTY? id={hid} ip={ip} name={name} ({e})")
EOF
```

粗略替代：`GET /api/hosts` 中 `authType=key` 且在修复部署前创建的主机，逐台
`POST /api/hosts/{id}/test`，`connected=false` 的即为待重录对象。

### 重录入口

- UI：主机列表 → 编辑 → 重新粘贴 SSH 私钥保存（`PUT /api/hosts/{id}`，服务层单次加密落库）；
- API：`PUT /api/hosts/{id}` 携带明文 `sshPrivateKey`。若同时传 `sshPassword` 会按
  凭据切换语义清除旧私钥，二选一传入即可。
- 重录后 `POST /api/hosts/{id}/test` 应返回 `connected=true`。

## ed25519 支持

MINA SSHD 2.12.1 将 ed25519 解析依赖 `net.i2p.crypto:eddsa` 声明为 optional，
classpath 缺失时 ed25519 私钥（OpenSSH 格式）解析失败。本修复在 `core/pom.xml`
显式引入 `net.i2p.crypto:eddsa:0.3.0`，ed25519 与 RSA 私钥均可解析
（单测 `SshKeyParsingSupportTest` 锁定）。需重新构建镜像使依赖生效。
