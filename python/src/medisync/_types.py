from __future__ import annotations

from typing_extensions import Literal


class NotGiven:
    """The default of an optional argument that was not passed. `None` is a value: it sends null."""

    _instance: NotGiven | None = None

    def __new__(cls) -> NotGiven:
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __bool__(self) -> Literal[False]:
        return False

    def __repr__(self) -> str:
        return "NOT_GIVEN"


NOT_GIVEN = NotGiven()
