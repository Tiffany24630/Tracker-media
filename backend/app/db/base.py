from datetime import datetime, timezone
from uuid import uuid4
from typing import Any
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy import DateTime
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.types import CHAR
from sqlalchemy import TypeDecorator

class GUID(TypeDecorator):
    impl = CHAR(36); cache_ok=True
    def load_dialect_impl(self, dialect): return dialect.type_descriptor(PGUUID(as_uuid=True) if dialect.name=='postgresql' else CHAR(36))
    def process_bind_param(self, value, dialect): return value if dialect.name=='postgresql' else (str(value) if value else None)
    def process_result_value(self, value, dialect):
        from uuid import UUID
        return value if value is None or isinstance(value, UUID) else UUID(str(value))
class Base(DeclarativeBase): pass
class UUIDPrimaryKeyMixin:
    id: Mapped[Any] = mapped_column(GUID(), primary_key=True, default=uuid4)
class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), onupdate=lambda: datetime.now(timezone.utc))
