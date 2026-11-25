#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""取 GitHub Actions 的构建日志 / 测试报告，并只打印有用的行。

为什么单独写一个脚本：
    这台机器 `git push` 走 HTTPS 会被持续重置，只能靠 API；而 API 的
    **job logs 和 artifact 下载都会 302 到 Azure Blob 的签名 URL**，
    跟随重定向时**必须去掉 Authorization 头**，否则 Azure 返回
    `InvalidAuthenticationInfo`（401）。这个坑 tools说明.md 里记过，
    我自己也复发过一次，所以固化成脚本。

用法：
    GH_TOKEN=... python tools/ci_log.py            # 最近一次运行：状态 + 失败行
    GH_TOKEN=... python tools/ci_log.py --tests    # 顺带下载并解析单测 XML
    GH_TOKEN=... python tools/ci_log.py --full     # 打印完整日志尾部
"""

import argparse
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import zipfile

REPO = "skyLibrakabrith/-"


def _request(url, token, accept="application/vnd.github+json"):
    """构造一个带凭据的请求。"""
    headers = {"User-Agent": "dsh"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if accept:
        headers["Accept"] = accept
    return urllib.request.Request(url, headers=headers)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise urllib.error.HTTPError(req.full_url, code, msg, headers, fp)


def api(url, token, tries=8):
    last = None
    for _ in range(tries):
        try:
            with urllib.request.urlopen(_request(url, token), timeout=180) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            last = e
            if 400 <= e.code < 500:
                raise
            time.sleep(3)
        except Exception as e:
            last = e
            time.sleep(3)
    raise SystemExit(f"API 不可达: {url} -> {last}")


def fetch_following_redirect(url, token):
    """先拿签名 URL（带 Authorization），再**不带** Authorization 去下载正文。"""
    opener = urllib.request.build_opener(NoRedirect)
    try:
        opener.open(_request(url, token), timeout=60)
        # 没有重定向，直接返回原文
        return urllib.request.urlopen(_request(url, token), timeout=180).read()
    except urllib.error.HTTPError as e:
        if e.code not in (301, 302, 303, 307, 308):
            raise
        signed = e.headers["Location"]
    # 关键：不带 Authorization
    req = urllib.request.Request(signed, headers={"User-Agent": "dsh"})
    return urllib.request.urlopen(req, timeout=300).read()


ERROR_PATTERNS = re.compile(
    r"^e: |^w: .*deprecat|error:|FAILURE|What went wrong|Execution failed|"
    r"> Task :.*FAILED|Caused by:|^> A problem occurred"
)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tests", action="store_true")
    ap.add_argument("--full", action="store_true")
    ap.add_argument("--tail", type=int, default=80)
    args = ap.parse_args()

    token = os.environ.get("GH_TOKEN", "").strip()
    runs = api(f"https://api.github.com/repos/{REPO}/actions/runs?per_page=3", token)
    run = runs["workflow_runs"][0]
    print(f"run #{run['run_number']} {run['head_sha'][:7]} status={run['status']} conclusion={run['conclusion']}")

    jobs = api(run["jobs_url"], token)["jobs"]
    for j in jobs:
        print(f"job {j['name']} -> {j['conclusion']}")
        for s in j["steps"]:
            print(f"   [{s['number']:>2}] {s['name']} -> {s['conclusion']}")

    log_text = None
    try:
        blob = fetch_following_redirect(
            f"https://api.github.com/repos/{REPO}/actions/jobs/{jobs[0]['id']}/logs", token
        )
        log_text = blob.decode("utf-8", "replace")
    except Exception as e:
        print(f"\n(取不到 job 日志: {type(e).__name__} {e})")

    if log_text:
        lines = log_text.split("\n")
        print(f"\n===== 日志 {len(lines)} 行，命中错误行 =====")
        for l in lines:
            if ERROR_PATTERNS.search(l):
                print(l.rstrip()[:400])
        if args.full:
            print("\n===== 日志尾部 =====")
            print("\n".join(lines[-args.tail:]))

    if args.tests:
        arts = api(run["artifacts_url"], token)["artifacts"]
        names = [a["name"] for a in arts]
        print(f"\nartifacts: {names}")
        art = next((a for a in arts if a["name"] == "test-report"), None)
        if not art:
            print("没有 test-report（可能编译就挂了，测试根本没跑）")
            return
        data = fetch_following_redirect(art["archive_download_url"], token)
        z = zipfile.ZipFile(io.BytesIO(data))
        for n in z.namelist():
            if not n.endswith(".xml"):
                continue
            body = z.read(n).decode("utf-8", "replace")
            m = re.search(r'tests="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"', body)
            print(f"\n{n.split('/')[-1]}: tests/failures/errors = {m.groups() if m else '?'}")
            for f in re.finditer(
                r'<testcase name="([^"]+)"[^>]*>\s*<failure message="([^"]*)"[^>]*>(.*?)</failure>',
                body,
                re.S,
            ):
                print(f"   FAILED: {f.group(1)}")
                print(f"      {f.group(2)[:300]}")
                ln = re.findall(r"\.kt:(\d+)", f.group(3))
                if ln:
                    print(f"      line {ln[0]}")


if __name__ == "__main__":
    main()
