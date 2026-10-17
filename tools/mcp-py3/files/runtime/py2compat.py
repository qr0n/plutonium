"""Python 2 file semantics for MCP's runtime under Python 3.

Python 2 read text files as raw bytes with no newline translation. Latin-1 maps every
byte to exactly one character, so decoding and re-encoding with it round-trips any file
unchanged, and newline='' turns off universal-newline translation.
"""
import builtins

_open = builtins.open


def open(file, mode='r', *args, **kwargs):
    if 'b' not in mode:
        kwargs.setdefault('encoding', 'latin-1')
        kwargs.setdefault('newline', '')
    return _open(file, mode, *args, **kwargs)
