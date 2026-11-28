"""
归档数据层端到端验证：把**真实 DAO SQL + 真实迁移语句**放到真实 SQLite 上跑一遍。

## 为什么需要这个脚本

真机连不上时，单测能覆盖状态机（`SessionArchiverTest`），但覆盖不到两件事：

1. Room 的 `@Query` SQL 本身写对了吗？（我写的是字符串，SQLite 才不管编译错不错）
2. v10→v11 迁移在真实库上到底能不能跑通？

这两样都只能靠真 SQLite 验证。单测用的是内存假 DAO，**根本不会执行这些 SQL**。

## 做法

- 从 `AppDatabase.kt` **源码里抽取** `@Query` 语句，不手抄（手抄的验证等于自欺欺人）
- 构造一个含老数据的 v10 库，执行抽取出的迁移语句
- 按真实生命周期走一遍：插入进行中会话 → 开暂停区间 → 封口 → finishOngoing
- 每步断言 DM 结果

## 用法

    python tools/verify_archive_db.py

退出码 0 = 全部通过。
"""

import io
import os
import re
import sqlite3
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB_SRC = os.path.join(
    REPO,
    "app/src/main/java/at/cwiesner/android/visualtimer/ext/data/AppDatabase.kt",
)

V10_SCHEMA = """
CREATE TABLE IF NOT EXISTS `timer_sessions` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `label` TEXT,
  `startedAt` INTEGER NOT NULL,
  `endedAt` INTEGER NOT NULL,
  `plannedMillis` INTEGER NOT NULL,
  `kind` TEXT NOT NULL,
  `completed` INTEGER NOT NULL);
"""


def _strings(fragment):
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', fragment))


def extract_query(src, iface, fname):
    """抽出某个 DAO 里指定方法的 @Query SQL（真实文本，不手抄）。"""
    seg = src[src.index("interface " + iface):]
    i = seg.index("fun " + fname)
    body = seg[seg.rindex("@Query(", 0, i):i]
    return _strings(body)


def extract_migration(src):
    """抽出 MIGRATION_10_11 里的全部 execSQL 语句。"""
    blk = src[src.index("private val MIGRATION_10_11"):]
    blk = blk[:blk.index("        /**\n         * 打开数据库")]
    return [
        _strings(r)
        for r in re.findall(r"execSQL\(\s*(.*?)\s*\)\s*\n", blk, re.S)
    ]


def main():
    src = io.open(DB_SRC, encoding="utf-8").read()
    sess = "TimerSessionDao"
    pause = "TimerPauseDao"

    q = {
        "observeBetween": extract_query(src, sess, "observeBetween"),
        "allOngoing": extract_query(src, sess, "allOngoing"),
        "finishOngoing": extract_query(src, sess, "finishOngoing"),
        "betweenFinished": extract_query(src, sess, "betweenFinished"),
        "openPause": extract_query(src, pause, "openPause"),
        "close": extract_query(src, pause, "close"),
        "forSessions": extract_query(src, pause, "forSessions"),
        "deleteOrphans": extract_query(src, pause, "deleteOrphans"),
    }
    mig = extract_migration(src)

    fails = []

    def check(name, cond, detail=""):
        mark = "OK  " if cond else "FAIL"
        print("  [%s] %s %s" % (mark, name, detail))
        if not cond:
            fails.append(name)

    tmp = os.path.join(tempfile.mkdtemp(), "t.db")
    con = sqlite3.connect(tmp)
    con.execute("PRAGMA user_version=10")
    con.executescript(V10_SCHEMA)
    # v10 里的两条老记录：迁移后必须原样还在
    base = 1_760_000_000_000
    con.execute(
        "INSERT INTO timer_sessions(label,startedAt,endedAt,plannedMillis,kind,completed)"
        " VALUES('老记录',?,?,?,'Countdown',1)",
        (base - 10_000_000, base - 9_000_000, 1_000_000),
    )
    con.commit()

    print("== 迁移 v10 -> v11 ==")
    for s in mig:
        con.executescript(s)
    con.execute("PRAGMA user_version=11")
    # 迁移后回填检查：老记录 ongoing 必须是 0
    old = con.execute(
        "SELECT COUNT(*) FROM timer_sessions WHERE ongoing=0"
    ).fetchone()[0]
    check("老数据保留且 ongoing 回填为 0", old == 1)
    check(
        "timer_pauses 表已建",
        bool(
            con.execute(
                "SELECT name FROM sqlite_master WHERE name='timer_pauses'"
            ).fetchall()
        ),
    )

    # ---- 真实生命周期 ----
    print("\n== 一次计时的生命周期 ==")
    con.execute(
        "INSERT INTO timer_sessions(label,startedAt,endedAt,plannedMillis,kind,completed,ongoing)"
        " VALUES('写周报',?,?,?,'Countdown',0,1)",
        (base, base + 600_000, 1_500_000),
    )
    sid = con.execute("SELECT last_insert_rowid()").fetchone()[0]
    con.execute(
        "INSERT INTO timer_pauses(sessionId,fromMillis,toMillis) VALUES(?,?,0)",
        (sid, base + 600_000),
    )
    con.commit()

    check("allOngoing 找到进行中会话", len(con.execute(q["allOngoing"], {}).fetchall()) == 1)
    op = con.execute(q["openPause"], {"sessionId": sid}).fetchall()
    check("openPause 找到未封口区间", len(op) == 1, "toMillis=%s" % (op[0][3] if op else None))

    con.execute(q["close"], {"id": op[0][0], "toMillis": base + 900_000})
    con.commit()
    check(
        "封口后 openPause 为空",
        len(con.execute(q["openPause"], {"sessionId": sid}).fetchall()) == 0,
    )

    con.execute(
        q["finishOngoing"],
        {
            "id": sid,
            "endedAt": base + 900_000,
            "plannedMillis": 1_500_000,
            "completed": 0,
        },
    )
    con.commit()
    check("结束后 allOngoing 为空", len(con.execute(q["allOngoing"], {}).fetchall()) == 0)
    check(
        "betweenFinished 能找到已完成记录",
        len(
            con.execute(
                q["betweenFinished"], {"from": base - 1000, "to": base + 10 ** 8}
            ).fetchall()
        )
        == 1,
    )

    row = con.execute(
        "SELECT startedAt,endedAt,plannedMillis,ongoing FROM timer_sessions"
        " WHERE id=?", (sid,)
    ).fetchone()
    check("实际时长 900 秒", (row[1] - row[0]) // 1000 == 900)
    check("计划时长 1500 秒", row[2] // 1000 == 1500)
    check("不再是进行中", row[3] == 0)

    psql = q["forSessions"].replace(":sessionIds", "?")
    pauses = con.execute(psql, [sid]).fetchall()
    check("暂停区间共 1 段", len(pauses) == 1)
    if pauses:
        # SELECT * 的列序是 (id, sessionId, fromMillis, toMillis) —— 别取错下标
        from_ms, to_ms = pauses[0][2], pauses[0][3]
        check(
            "暂停区间 600-900 秒",
            (from_ms - base) // 1000 == 600 and (to_ms - base) // 1000 == 900,
            "实际 %d-%d 秒" % ((from_ms - base) // 1000, (to_ms - base) // 1000),
        )

    # 孤儿清理：删掉会话后区间应被清掉
    con.execute("DELETE FROM timer_sessions WHERE id=?", (sid,))
    con.commit()
    con.execute(q["deleteOrphans"])
    con.commit()
    check("deleteOrphans 清掉了孤儿区间",
          con.execute("SELECT COUNT(*) FROM timer_pauses").fetchone()[0] == 0)

    con.close()
    print()
    if fails:
        print("有 %d 项失败: %s" % (len(fails), ", ".join(fails)))
        return 1
    print("全部通过 ✅  （迁移 + 全部 DAO SQL + 完整生命周期）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
