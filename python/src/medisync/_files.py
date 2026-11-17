from __future__ import annotations

import io
import os
from pathlib import Path
from typing import IO, Any, Callable, Optional, Tuple, Union, cast

FileContent = Union[bytes, IO[bytes]]
FileTypes = Union[
    FileContent,
    "os.PathLike[str]",
    Tuple[str, FileContent],
    Tuple[str, FileContent, str],
]
"""A file to upload: bytes, an open binary file, a path, or a tuple (filename, content[, content type])."""

HttpxFile = Tuple[str, Union[bytes, IO[bytes]], str]


def is_file(value: object) -> bool:
    if isinstance(value, (bytes, bytearray, io.IOBase, os.PathLike)):
        return True
    return isinstance(value, tuple) and len(value) in (2, 3) and isinstance(value[0], str)  # pyright: ignore[reportUnknownArgumentType]


def file_opener(value: Any, field: str) -> Callable[[], HttpxFile]:
    """A function that gives the file for one attempt. A retry reads the file again from its start."""
    filename: Optional[str] = None
    content_type: str = "application/octet-stream"
    content: Any = value
    if isinstance(value, tuple):
        parts: Tuple[Any, ...] = value  # pyright: ignore[reportUnknownVariableType]
        filename = str(parts[0])
        content = parts[1]
        if len(parts) == 3:
            content_type = str(parts[2])
    if isinstance(content, os.PathLike):
        path = Path(cast("os.PathLike[str]", content))
        name: str = filename or path.name

        def from_path() -> HttpxFile:
            return (name, path.read_bytes(), content_type)

        return from_path
    if isinstance(content, (bytes, bytearray)):
        data = bytes(cast(bytes, content))
        name = filename or field

        def from_bytes() -> HttpxFile:
            return (name, data, content_type)

        return from_bytes
    stream = cast(IO[bytes], content)
    start: Optional[int] = stream.tell() if stream.seekable() else None
    name = filename or os.path.basename(str(getattr(stream, "name", field))) or field

    def from_stream() -> HttpxFile:
        if start is not None:
            stream.seek(start)
        return (name, stream, content_type)

    return from_stream
