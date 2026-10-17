"""The re module with Python 2 str semantics: \\w, \\d, \\s and \\b match ASCII only."""
import re as _re
from re import error, escape, purge, IGNORECASE, MULTILINE, DOTALL, VERBOSE, UNICODE, ASCII, I, M, S, X, U, A


def compile(pattern, flags=0):
    if isinstance(pattern, _re.Pattern):
        return pattern
    if isinstance(pattern, str) and not flags & _re.UNICODE:
        flags |= _re.ASCII
    return _re.compile(pattern, flags)


def search(pattern, string, flags=0):
    return compile(pattern, flags).search(string)


def match(pattern, string, flags=0):
    return compile(pattern, flags).match(string)


def fullmatch(pattern, string, flags=0):
    return compile(pattern, flags).fullmatch(string)


def sub(pattern, repl, string, count=0, flags=0):
    return compile(pattern, flags).sub(repl, string, count)


def subn(pattern, repl, string, count=0, flags=0):
    return compile(pattern, flags).subn(repl, string, count)


def split(pattern, string, maxsplit=0, flags=0):
    return compile(pattern, flags).split(string, maxsplit)


def findall(pattern, string, flags=0):
    return compile(pattern, flags).findall(string)


def finditer(pattern, string, flags=0):
    return compile(pattern, flags).finditer(string)
