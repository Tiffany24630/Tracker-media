from __future__ import annotations

from datetime import date
from typing import TYPE_CHECKING, Any

from sqlalchemy import JSON, Date, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, TimestampMixin, UUIDPrimaryKeyMixin
from app.models.enums import MediaStatus, MediaType

if TYPE_CHECKING:
    from app.models.library_entry import UserMedia
    from app.models.media_details import (
        MediaExternalId,
        MediaGenre,
        MediaRelation,
        MediaTitle,
        MediaUnit,
    )
    from app.models.tracking import UserListItem, UserRating, UserReview


class Media(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    """A provider-independent universal creative work."""

    __tablename__ = "media"

    media_type: Mapped[MediaType] = mapped_column(String(30), index=True)
    title: Mapped[str] = mapped_column(String(500), index=True)
    match_key: Mapped[str] = mapped_column(String(700), unique=True, index=True)
    description: Mapped[str | None] = mapped_column(Text)
    release_year: Mapped[int | None] = mapped_column(Integer)
    release_date: Mapped[date | None] = mapped_column(Date, index=True)
    status: Mapped[MediaStatus] = mapped_column(
        String(30), default=MediaStatus.UNKNOWN, server_default=MediaStatus.UNKNOWN
    )
    original_language: Mapped[str | None] = mapped_column(String(16), index=True)
    languages: Mapped[list[str]] = mapped_column(JSON, default=list)
    cover_url: Mapped[str | None] = mapped_column(String(2048))
    metadata_: Mapped[dict[str, Any]] = mapped_column("metadata", JSON, default=dict)

    external_ids: Mapped[list[MediaExternalId]] = relationship(
        back_populates="media", cascade="all, delete-orphan"
    )
    titles: Mapped[list[MediaTitle]] = relationship(
        back_populates="media", cascade="all, delete-orphan"
    )
    genres: Mapped[list[MediaGenre]] = relationship(
        back_populates="media", cascade="all, delete-orphan"
    )
    units: Mapped[list[MediaUnit]] = relationship(
        back_populates="media", cascade="all, delete-orphan"
    )
    outgoing_relations: Mapped[list[MediaRelation]] = relationship(
        foreign_keys="MediaRelation.source_media_id",
        back_populates="source_media",
        cascade="all, delete-orphan",
    )
    incoming_relations: Mapped[list[MediaRelation]] = relationship(
        foreign_keys="MediaRelation.target_media_id",
        back_populates="target_media",
        cascade="all, delete-orphan",
    )
    library_entries: Mapped[list[UserMedia]] = relationship(
        back_populates="media", cascade="all, delete-orphan"
    )
    list_items: Mapped[list[UserListItem]] = relationship(
        back_populates="media", cascade="all, delete-orphan"
    )
    ratings: Mapped[list[UserRating]] = relationship(
        back_populates="media", cascade="all, delete-orphan"
    )
    reviews: Mapped[list[UserReview]] = relationship(
        back_populates="media", cascade="all, delete-orphan"
    )

    @property
    def original_title(self) -> str | None:
        return next((item.title for item in self.titles if item.title_type == "original"), None)

    @property
    def provider(self) -> str | None:
        return self.external_ids[0].provider if self.external_ids else None

    @property
    def provider_id(self) -> str | None:
        return self.external_ids[0].external_id if self.external_ids else None


MediaItem = Media
