"""把固定 debug keystore 上传为 GitHub Actions secret（DEBUG_KEYSTORE_B64）。

GitHub 的 secret 必须先用它给的公钥做 libsodium sealed box 加密，不能明文 PUT。

用法：python set_secret.py <b64文件路径>
需要环境变量 GH_TOKEN。
"""

import base64
import json
import os
import sys
import urllib.request

from nacl import encoding, public

REPO = "skyLibrakabrith/-"
SECRET_NAME = "DEBUG_KEYSTORE_B64"
API = "https://api.github.com"


def call(method, path, token, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        API + path,
        data=data,
        method=method,
        headers={
            "Authorization": f"token {token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "dsh",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = r.read()
        return json.loads(raw) if raw else {}


def main():
    token = os.environ.get("GH_TOKEN")
    if not token:
        print("需要 GH_TOKEN", file=sys.stderr)
        return 1
    if len(sys.argv) < 2:
        print("用法: python set_secret.py <b64文件路径>", file=sys.stderr)
        return 1

    value = open(sys.argv[1], "r", encoding="ascii").read().strip()
    print(f"待上传值长度: {len(value)}")

    # 1) 取仓库公钥
    key = call("GET", f"/repos/{REPO}/actions/secrets/public-key", token)
    print(f"公钥 key_id: {key['key_id']}")

    # 2) 用公钥加密
    pk = public.PublicKey(key["key"].encode(), encoding.Base64Encoder())
    sealed = public.SealedBox(pk).encrypt(value.encode())
    encrypted = base64.b64encode(sealed).decode()
    print(f"加密后长度: {len(encrypted)}")

    # 3) PUT
    call(
        "PUT",
        f"/repos/{REPO}/actions/secrets/{SECRET_NAME}",
        token,
        {"encrypted_value": encrypted, "key_id": key["key_id"]},
    )
    print(f"已写入 secret: {SECRET_NAME}")

    # 4) 回读确认存在（GitHub 不返回值，只能看到名字和更新时间）
    lst = call("GET", f"/repos/{REPO}/actions/secrets", token)
    names = [s["name"] for s in lst.get("secrets", [])]
    print(f"仓库现有 secrets: {names}")
    print("OK" if SECRET_NAME in names else "!! 写入后没在列表里看到，请检查权限")
    return 0


if __name__ == "__main__":
    sys.exit(main())
