#!/usr/bin/env python3
"""변경 경로의 소유자를 보고 승인이 필요한지 판정한다(#1321).

자기가 유일한 소유자인 경로만 고친 PR 은 승인 없이 통과시킨다. 남의 도메인이나
공용 경로가 섞이면 그 경로 소유자의 승인을 요구한다. 소유자가 아닌 사람의 승인으로는
통과하지 않는다.

CODEOWNERS 는 base 브랜치에서 읽는다. PR 안에서 CODEOWNERS 를 고쳐 자기에게
유리하게 바꾸는 길을 막는다. GitHub 의 코드 소유자 판정도 같은 기준을 쓴다.

모르는 패턴을 만나면 통과시키지 않고 멈춘다. 지금 이 저장소의 CODEOWNERS 는 세 꼴만
쓰는데(`*`, 디렉터리 접두사, 정확한 파일 경로) 나중에 `**` 나 확장자 패턴이 들어오면
조용히 틀린 소유자를 내놓을 수 있다. 그 조용한 고장이 #1069 와 #1073 에서 몇 달을 숨었다.
"""
import json
import os
import re
import subprocess
import sys


def run(*args):
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode != 0:
        die(f"명령이 실패했다: {' '.join(args)}\n{r.stderr.strip()}")
    return r.stdout


def die(msg):
    print(f"::error::{msg}")
    sys.exit(1)


def lines(out):
    return [x for x in out.splitlines() if x.strip()]


# --- CODEOWNERS ------------------------------------------------------------

GLOB = set("*?[]!")

# `**/이름/` 꼴이다. 깊이를 가리지 않고 그 이름의 디렉터리 아래를 모두 잡는다.
# 도메인 하나가 코드, 마이그레이션, 테스트, 설계 문서, 부하 시험에 흩어져 있어
# 경로마다 한 줄씩 적으면 디렉터리가 늘 때마다 CODEOWNERS 를 함께 고쳐야 한다(#1331).
ANY_DIR = re.compile(r"\*\*/[A-Za-z0-9._-]+/")


def parse_codeowners(text):
    """(종류, 패턴, 소유자들) 목록을 파일에 적힌 순서대로 돌려준다.

    맞는 줄이 여럿이면 마지막 줄이 이긴다. 그 판정은 호출하는 쪽이 한다.
    """
    rules = []
    for no, raw in enumerate(text.splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        pattern, owners = parts[0], parts[1:]
        if not owners:
            die(f"CODEOWNERS {no}줄: 소유자가 없다 -> {line}")
        for o in owners:
            if not o.startswith("@"):
                die(f"CODEOWNERS {no}줄: 이메일 소유자는 지원하지 않는다 -> {o}")
            if "/" in o:
                die(f"CODEOWNERS {no}줄: 팀 소유자는 지원하지 않는다 -> {o}")
        if pattern == "*":
            kind = "all"
        elif ANY_DIR.fullmatch(pattern):
            kind = "anydir"
        elif pattern.startswith("/") and pattern.endswith("/") and not (set(pattern) & GLOB):
            kind = "prefix"
        elif pattern.startswith("/") and not (set(pattern) & GLOB):
            kind = "exact"
        else:
            die(
                f"CODEOWNERS {no}줄: 지원하지 않는 패턴이다 -> {pattern}\n"
                "이 게이트는 `*`, `**/이름/`, `/디렉터리/`, `/정확한/파일` 네 꼴만 판정한다. "
                "다른 꼴을 쓰려면 review-gate.py 에 그 꼴을 더하고 확인까지 한 뒤에 쓴다."
            )
        rules.append((kind, pattern, [o[1:] for o in owners]))
    if not rules:
        die("CODEOWNERS 가 비어 있다")
    return rules


def is_contract(path):
    """도메인 간 접점이다. 소유자 블록이 호출자까지 담고 있어 작성자를 빼지 않는다."""
    return "/contract/" in "/" + path


def owners_of(path, rules):
    """그 경로의 소유자. 맞는 줄이 없으면 None."""
    found = None
    probe = "/" + path
    for kind, pattern, owners in rules:
        if kind == "all":
            found = owners
        elif kind == "anydir" and ("/" + pattern[3:]) in probe:
            found = owners
        elif kind == "prefix" and probe.startswith(pattern):
            found = owners
        elif kind == "exact" and probe == pattern:
            found = owners
    return found


# --- 판정 ------------------------------------------------------------------

def main():
    repo = os.environ["REPO"]
    number = os.environ["PR_NUMBER"]

    pr = json.loads(run("gh", "api", f"repos/{repo}/pulls/{number}"))
    author = pr["user"]["login"]
    base = pr["base"]["ref"]

    owners_text = run(
        "gh", "api",
        f"repos/{repo}/contents/.github/CODEOWNERS?ref={base}",
        "-H", "Accept: application/vnd.github.raw",
    )
    rules = parse_codeowners(owners_text)

    files = lines(run(
        "gh", "api", "--paginate",
        f"repos/{repo}/pulls/{number}/files",
        "-q", ".[].filename",
    ))
    if not files:
        die("변경된 파일이 없다. 판정할 것이 없으면 통과시키지 않는다")

    # 경로마다 소유자를 모은다.
    #
    # 공동 소유 경로는 작성자가 그 가운데 하나면 넘어간다. `auth` 와 `ops` 처럼 둘이 함께
    # 갖는 경로를 그 담당이 고칠 때마다 상대의 승인을 기다리게 할 이유가 없다.
    #
    # `contract` 만 예외다. CODEOWNERS 가 그 블록을 따로 둔 이유를 적어 두었다.
    # "contract 는 도메인 간 유일한 접점이다. 그 모양이 바뀌면 부르는 도메인이 깨진다.
    #  소유자만 요청하면 깨질 사람이 모르는 채로 병합된다."
    # 그래서 contract 는 작성자가 소유자여도 다른 소유자(부르는 쪽)의 승인을 받는다.
    others = set()
    unowned = []
    for f in files:
        o = owners_of(f, rules)
        if o is None:
            unowned.append(f)
            continue
        if is_contract(f):
            others |= {x for x in o if x != author}
        elif author not in o:
            others |= set(o)
    if unowned:
        die("맞는 CODEOWNERS 규칙이 없는 경로가 있다:\n  " + "\n  ".join(unowned))

    print(f"작성자: {author}   base: {base}   변경 파일: {len(files)}개")

    if not others:
        print("::notice::작성자가 유일한 소유자인 경로만 바꿨다. 승인 없이 통과한다.")
        return

    print("승인을 줄 수 있는 소유자: " + ", ".join(sorted(others)))

    # 사람마다 마지막 상태만 본다. COMMENTED 는 상태를 바꾸지 않아 세지 않는다.
    # 승인이 해제되면 state 가 DISMISSED 로 바뀐다. dismiss_stale_reviews 가 켜져 있어
    # 새 커밋이 오면 앞선 승인이 그렇게 풀린다.
    latest = {}
    for row in lines(run(
        "gh", "api", "--paginate",
        f"repos/{repo}/pulls/{number}/reviews",
        "-q", '.[] | "\\(.state)\\t\\(.user.login)"',
    )):
        state, user = row.split("\t", 1)
        if state in ("APPROVED", "CHANGES_REQUESTED", "DISMISSED"):
            latest[user] = state

    blocking = sorted(u for u, s in latest.items() if s == "CHANGES_REQUESTED")
    if blocking:
        die("변경 요청이 살아 있다: " + ", ".join(blocking))

    approvers = {u for u, s in latest.items() if s == "APPROVED"}
    hit = approvers & others
    if hit:
        print(f"::notice::소유자 승인을 받았다: {', '.join(sorted(hit))}")
        return

    if approvers:
        print("소유자가 아닌 승인: " + ", ".join(sorted(approvers - others)))
    die(
        "이 PR 은 작성자가 소유하지 않은 경로를 건드린다. "
        "다음 가운데 한 명의 승인이 필요하다: " + ", ".join(sorted(others))
    )


if __name__ == "__main__":
    main()
