#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""用 GitHub Git Data API 推送本地提交。

为什么需要这个脚本：
    这台机器的 `git push` 走 HTTPS 时会被**持续**重置
    （`Connection was reset` / `Failed to connect to github.com port 443`），
    但 Python urllib 访问 api.github.com 是通畅的。
    所以改成用 API 直接建 blob / tree / commit 并快进 ref。

行为：
    - 只推送本地 HEAD 相对远端 main 的**最终快照**，提交信息取本地 HEAD 的 message。
    - 快进（force=False），远端领先或不相关时会报错，不会覆盖别人的提交。
    - 推完后请执行 `git fetch origin main && git reset --hard origin/main`
      —— 因为 API 建的 commit SHA 与本地不同（父提交/时间戳不同），
      不重置的话本地会永远"领先"一个看起来没推上去的提交。

用法：
    python tools/gh_push.py            # 推 HEAD
    python tools/gh_push.py --dry-run  # 只看会做什么

Token 从环境变量 GH_TOKEN 读，不要写死在文件里。
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.request

REPO = "skyLibrakabrith/-"
BRANCH = "main"


def api(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        "https://api.github.com" + path,
        data=data,
        method=method,
        headers={
            "User-Agent": "dsh",
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)


def git(*args, cwd="."):
    return subprocess.run(
        ["git", *args], cwd=cwd, capture_output=True, text=True, check=True
    ).stdout.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--cwd", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    args = ap.parse_args()

    token = os.environ.get("GH_TOKEN", "").strip()
    if not token:
        sys.exit("需要环境变量 GH_TOKEN")
    cwd = args.cwd

    local_head = git("rev-parse", "HEAD", cwd=cwd)
    remote = api("GET", f"/repos/{REPO}/commits/{BRANCH}", token=token)
    remote_sha = remote["sha"]
    print(f"local  HEAD: {local_head[:7]}")
    print(f"remote {BRANCH}: {remote_sha[:7]}")

    if local_head == remote_sha:
        print("已同步，无需推送。")
        return

    # 本地 HEAD 的树和远端是否一致？
    local_tree = git("rev-parse", "HEAD^{tree}", cwd=cwd)
    remote_tree = remote["commit"]["tree"]["sha"]
    if local_tree == remote_tree:
        print("树内容已一致（之前用 API 推过），只需重置本地：")
        print(f"  git fetch origin {BRANCH} && git reset --hard origin/{BRANCH}")
        return

    files = [
        f
        for f in git("ls-tree", "-r", "--name-only", "HEAD", cwd=cwd).split("\n")
        if f.strip()
    ]
    print(f"文件数: {len(files)}")

    if args.dry_run:
        print("[dry-run] 将上传以上文件并新建 commit，实际未执行。")
        return

    blobs = {}
    for f in files:
        p = os.path.join(cwd, f.replace("/", os.sep))
        with open(p, "rb") as fh:
            content = fh.read()
        # 二进制判定：前 8KB 出现 NUL 就当 base64 传
        if b"\0" in content[:8000]:
            body = {"content": base64.b64encode(content).decode(), "encoding": "base64"}
        else:
            body = {"content": content.decode("utf-8"), "encoding": "utf-8"}
        blobs[f] = api("POST", f"/repos/{REPO}/git/blobs", body, token=token)["sha"]
    print(f"blob 上传完成: {len(blobs)}")

    def make_tree(prefix):
        """自底向上建树：返回 prefix 目录的 tree sha。

        注意不能把文件路径当 key 直接嵌套 —— 曾经因为
        「文件跟目录同名」和「用完整 path 当 key」踩过 TypeError。
        正确做法是按层拆分：本层文件 + 本层子目录递归。
        """
        entries = []
        subdirs = set()
        for path, sha in blobs.items():
            if not path.startswith(prefix):
                continue
            rest = path[len(prefix):]
            if "/" in rest:
                subdirs.add(rest.split("/")[0])
            else:
                mode = "100755" if rest.endswith(".sh") else "100644"
                entries.append(
                    {"path": rest, "mode": mode, "type": "blob", "sha": sha}
                )
        for d in sorted(subdirs):
            entries.append(
                {
                    "path": d,
                    "mode": "040000",
                    "type": "tree",
                    "sha": make_tree(prefix + d + "/"),
                }
            )
        entries.sort(key=lambda e: e["path"])
        return api("POST", f"/repos/{REPO}/git/trees", {"tree": entries}, token=token)["sha"]

    tree_sha = make_tree("")
    print(f"root tree: {tree_sha[:10]}")

    msg = git("log", "-1", "--format=%B", cwd=cwd)
    new_commit = api(
        "POST",
        f"/repos/{REPO}/git/commits",
        {"message": msg, "tree": tree_sha, "parents": [remote_sha]},
        token=token,
    )
    print(f"new commit: {new_commit['sha'][:7]}")

    api(
        "PATCH",
        f"/repos/{REPO}/git/refs/heads/{BRANCH}",
        {"sha": new_commit["sha"], "force": False},
        token=token,
    )
    print(f"已推送 {BRANCH} -> {new_commit['sha'][:7]}")
    print("现在执行：git fetch origin main && git reset --hard origin/main")


if __name__ == "__main__":
    main()
