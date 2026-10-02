#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""社区 #487 静态门禁：执行器读取 DataX 子进程输出的顺序必须是"两条流都先开线程读"。

为什么用静态检查而不是单测：真正的死锁要有一个会往 stderr 狂写的子进程才复现得出来
（本机没有装 DataX，A/B 取证用的是同构探针 tmp/StderrFloodProbe.java，见开发日志）。
但"stderr 线程晚于 futureTask.get() 启动"这个**顺序**在源码里是可直接判定的，
而且一旦上游合并或后人重排就悄悄退化，所以需要一个每次 recheck 都会跑、且不依赖 JDK 的门禁。

判定（只看真正的代码行，注释里出现的方法名不算）：ExecutorJobHandler 里
  1) errThread.start() 必须出现在 futureTask.get() 之前；
  2) 两者都必须存在（结构被换掉时宁可报"找不到"也不报通过）。
"""
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    # Windows 控制台默认 cp936，中文结论会变乱码，门禁输出必须可读
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

from pathlib import Path

PATH = (Path(__file__).resolve().parents[2]
        / "datax-executor/src/main/java/com/wugui/datax/executor/service/jobhandler/ExecutorJobHandler.java")

START_RE = re.compile(r"\berrThread\.start\s*\(\s*\)")
GET_RE = re.compile(r"\bfutureTask\.get\s*\(")


def is_code(stripped_line):
    """整行注释不参与判定：注释里写到 futureTask.get() 不能把门禁带偏。"""
    return not (stripped_line.startswith("//")
                or stripped_line.startswith("*")
                or stripped_line.startswith("/*"))


def main():
    try:
        with PATH.open(encoding="utf-8") as f:
            lines = f.read().splitlines()
    except IOError as e:
        print("FAIL: 读不到 %s：%s" % (PATH, e))
        return 1

    start_line = None
    get_line = None
    for lineno, s in enumerate(lines, 1):
        if not is_code(s.strip()):
            continue
        if start_line is None and START_RE.search(s):
            start_line = lineno
        if get_line is None and GET_RE.search(s):
            get_line = lineno

    if start_line is None:
        print("FAIL: %s 里找不到 errThread.start()，stderr 读取线程被删了？" % PATH.name)
        return 1
    if get_line is None:
        print("FAIL: %s 里找不到 futureTask.get()，结构已变，本门禁需同步更新" % PATH.name)
        return 1

    if start_line > get_line:
        print("FAIL: 第 %d 行才启动 stderr 线程，却已在第 %d 行阻塞等 stdout 结果。" % (start_line, get_line))
        print("      子进程 stderr 写满管道缓冲（Linux 默认 64KB）后会卡在 write，")
        print("      stdout 永不 EOF，父线程永久挂起 —— 这就是社区 #487。")
        return 1

    print("OK   stderr 线程(第 %d 行) 早于 futureTask.get()(第 %d 行) 启动" % (start_line, get_line))
    print("PASS: #487 管道死锁顺序检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
