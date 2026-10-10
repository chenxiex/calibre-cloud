#!/usr/bin/env python3
"""Compare two Calibre metadata.db files table by table without modifying either.

Usage: python3 -I calibre_db_diff.py BEFORE.db AFTER.db [--limit N]

Both files are opened read-only and immutable. The schema (sqlite_master), user_version and
application_id are compared first, then every non-virtual table (FTS shadow tables included) as a
multiset of rows whose values keep their storage class. Rows are printed by table as "-" (only in
BEFORE) and "+" (only in AFTER). Exit status is 0 when the databases are equal, 1 when they differ
and 2 on usage errors.
"""

import argparse
import sqlite3
import sys
from collections import Counter
from pathlib import Path
from urllib.parse import quote


def connect(path):
    uri = "file:" + quote(str(Path(path).resolve())) + "?mode=ro&immutable=1"
    return sqlite3.connect(uri, uri=True)


def ident(name):
    return '"' + name.replace('"', '""') + '"'


def schema(db):
    return db.execute("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name").fetchall()


def tables(db):
    rows = db.execute("SELECT name, sql FROM sqlite_master WHERE type = 'table' ORDER BY name").fetchall()
    return [name for name, sql in rows if not (sql or "").upper().startswith("CREATE VIRTUAL TABLE")]


def columns(db, table):
    return [row[1] for row in db.execute(f"PRAGMA table_info({ident(table)})")]


def typed_rows(db, table, names):
    projection = ", ".join(f"typeof({ident(n)}), {ident(n)}" for n in names)
    rows = db.execute(f"SELECT {projection} FROM {ident(table)}").fetchall()
    return Counter(tuple(zip(row[0::2], row[1::2])) for row in rows)


def show(row, names):
    return ", ".join(f"{name}={value!r}" for name, (_, value) in zip(names, row))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("before")
    parser.add_argument("after")
    parser.add_argument("--limit", type=int, default=20, help="rows printed per table and side")
    args = parser.parse_args()
    for path in (args.before, args.after):
        if not Path(path).is_file():
            print(f"not a file: {path}", file=sys.stderr)
            return 2
    before, after = connect(args.before), connect(args.after)
    differs = False
    if schema(before) != schema(after):
        differs = True
        print("schema differs:")
        for entry in sorted(set(schema(before)) - set(schema(after))):
            print(f"  - {entry[0]} {entry[1]}")
        for entry in sorted(set(schema(after)) - set(schema(before))):
            print(f"  + {entry[0]} {entry[1]}")
    for pragma in ("user_version", "application_id"):
        a = before.execute(f"PRAGMA {pragma}").fetchone()[0]
        b = after.execute(f"PRAGMA {pragma}").fetchone()[0]
        if a != b:
            differs = True
            print(f"{pragma}: {a} -> {b}")
    shared = [t for t in tables(before) if t in set(tables(after))]
    for table in shared:
        names = columns(before, table)
        if names != columns(after, table):
            differs = True
            print(f"[{table}] columns differ")
            continue
        old, new = typed_rows(before, table, names), typed_rows(after, table, names)
        removed, added = old - new, new - old
        if not removed and not added:
            continue
        differs = True
        print(f"[{table}] -{sum(removed.values())} +{sum(added.values())}")
        for sign, rows in (("-", removed), ("+", added)):
            for row in sorted(rows.elements(), key=repr)[: args.limit]:
                print(f"  {sign} {show(row, names)}")
    if not differs:
        print("identical")
    return 1 if differs else 0


if __name__ == "__main__":
    sys.exit(main())
