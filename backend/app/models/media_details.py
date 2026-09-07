from __future__ import annotations

from datetime import date
from typing import TYPE_CHECKING, Any
from uuid import UUID

from sqlalchemy import (
    JSON,
    Boolean,
    CheckConstraint,
    Date,
    Float,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, TimestampMixin, UUIDPrimaryKeyMixin
from app.models.enums import MediaTitleType, MediaUnitType

if TYPE_CHECKING:
    from app.models.media import Media


class MediaExternalId(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "media_external_ids"
    __table_args__ = (
        UniqueConstraint("provider", "external_id"),
        UniqueConstraint("media_id", "provider", "external_id"),
    )

    media_id: Mapped[UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), index=True)
    provider: Mapped[str] = mapped_column(String(80), index=True)
    external_id: Mapped[str] = mapped_column(String(255))
    url: Mapped[str | None] = mapped_column(String(2048))

    media: Mapped[Media] = relationship(back_populates="external_ids")


class MediaTitle(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "media_titles"
    __table_args__ = (
        UniqueConstraint(
            "media_id",
            "title",
            "language_code",
            "title_type",
            postgresql_nulls_not_distinct=True,
        ),
    )

    media_id: Mapped[UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), index=True)
    title: Mapped[str] = mapped_column(String(500), index=True)
    normalized_title: Mapped[str] = mapped_column(String(500), index=True)
    language_code: Mapped[str | None] = mapped_column(String(16))
    title_type: Mapped[MediaTitleType] = mapped_column(String(30))
    is_preferred: Mapped[bool] = mapped_column(Boolean, default=False, server_default="false")

    media: Mapped[Media] = relationship(back_populates="titles")


class MediaGenre(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "media_genres"
    __table_args__ = (UniqueConstraint("media_id", "slug"),)

    media_id: Mapped[UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), index=True)
    name: Mapped[str] = mapped_column(String(100))
    slug: Mapped[str] = mapped_column(String(100), index=True)

    media: Mapped[Media] = relationship(back_populates="genres")


class MediaRelation(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "media_relations"
    __table_args__ = (
        UniqueConstraint("source_media_id", "target_media_id", "relation_type"),
        CheckConstraint("source_media_id <> target_media_id", name="different_media"),
    )

    source_media_id: Mapped[UUID] = mapped_column(
        ForeignKey("media.id", ondelete="CASCADE"), index=True
    )
    target_media_id: Mapped[UUID] = mapped_column(
        ForeignKey("media.id", ondelete="CASCADE"), index=True
    )
    relation_type: Mapped[str] = mapped_column(String(50), index=True)
    metadata_: Mapped[dict[str, Any]] = mapped_column("metadata", JSON, default=dict)

    source_media: Mapped[Media] = relationship(
        foreign_keys=[source_media_id], back_populates="outgoing_relations"
    )
    target_media: Mapped[Media] = relationship(
        foreign_keys=[target_media_id], back_populates="incoming_relations"
    )


class MediaUnit(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "media_units"
    __table_args__ = (
        UniqueConstraint(
            "media_id",
            "parent_id",
            "unit_type",
            "number",
            postgresql_nulls_not_distinct=True,
        ),
    )

    media_id: Mapped[UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), index=True)
    parent_id: Mapped[UUID | None] = mapped_column(
        ForeignKey("media_units.id", ondelete="CASCADE"), index=True
    )
    unit_type: Mapped[MediaUnitType] = mapped_column(String(30), index=True)
    number: Mapped[float] = mapped_column(Float)
    title: Mapped[str | None] = mapped_column(String(500))
    description: Mapped[str | None] = mapped_column(Text)
    release_date: Mapped[date | None] = mapped_column(Date)
    duration_seconds: Mapped[int | None] = mapped_column(Integer)
    metadata_: Mapped[dict[str, Any]] = mapped_column("metadata", JSON, default=dict)

    media: Mapped[Media] = relationship(back_populates="units")
    parent: Mapped[MediaUnit | None] = relationship(
        remote_side="MediaUnit.id", back_populates="children"
    )
    children: Mapped[list[MediaUnit]] = relationship(
        back_populates="parent", cascade="all, delete-orphan"
    )
