from __future__ import annotations

import logging
from typing import Any, Dict, Type, TypeVar, cast

import pydantic
from pydantic import ConfigDict

log = logging.getLogger("medisync")

T = TypeVar("T")


class BaseModel(pydantic.BaseModel):
    """Base of every model. Unknown fields are kept, so a newer API does not break an older SDK."""

    model_config = ConfigDict(extra="allow", populate_by_name=True, protected_namespaces=(), arbitrary_types_allowed=True)

    def to_dict(self) -> Dict[str, Any]:
        """The model as JSON-ready data, with the field names of the API."""
        return self.model_dump(mode="json", by_alias=True, exclude_unset=True)


_adapters: Dict[Any, pydantic.TypeAdapter[Any]] = {}


def construct(type_: Any, value: Any) -> Any:
    """Reads an answer into `type_`. An answer that does not match the spec is kept as it came, with a log warning."""
    if value is None or type_ is None or type_ is Any:
        return value
    if isinstance(type_, type) and issubclass(type_, BaseModel):
        try:
            return type_.model_validate(value)
        except pydantic.ValidationError as e:
            log.warning("The answer does not match %s (%s errors); the model is built without checks.", type_.__name__, e.error_count())
            if isinstance(value, dict):
                return type_.model_construct(**cast(Dict[str, Any], value))
            return value
    adapter = _adapters.get(type_)
    if adapter is None:
        adapter = pydantic.TypeAdapter[Any](type_)  # pyright: ignore[reportUnknownArgumentType]
        _adapters[type_] = adapter
    try:
        return adapter.validate_python(value)
    except pydantic.ValidationError:
        log.warning("The answer does not match the expected type; it is returned as it came.")
        return value


def construct_as(type_: Type[T], value: Any) -> T:
    return cast(T, construct(type_, value))
