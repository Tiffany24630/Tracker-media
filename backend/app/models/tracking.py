from __future__ import annotations

from datetime import datetime
from typing import TYPE_CHECKING, Any
from uuid import UUID

from sqlalchemy import (
    JSON,
    Boolean,
    CheckConstraint,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, TimestampMixin, UUIDPrimaryKeyMixin
from app.models.enums import IntegrationStatus, ProgressUnit, SyncJobStatus

if TYPE_CHECKING:
    from app.models.library_entry import UserMedia
    from app.models.media import Media
    from app.models.user import User


class UserProgress(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "user_progress"
    __table_args__ = (
        CheckConstraint("value >= 0", name="value_non_negative"),
        CheckConstraint("total IS NULL OR total >= 0", name="total_non_negative"),
    )

    user_media_id: Mapped[UUID] = mapped_column(
        ForeignKey("user_media.id", ondelete="CASCADE"), index=True
    )
    value: Mapped[float] = mapped_column(Float)
    total: Mapped[float | None] = mapped_column(Float)
    unit: Mapped[ProgressUnit] = mapped_column(String(30), default=ProgressUnit.ITEM)
    note: Mapped[str | None] = mapped_column(String(500))
    occurred_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)

    user_media: Mapped[UserMedia] = relationship(back_populates="progress_entries")


class UserList(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "user_lists"
    __table_args__ = (UniqueConstraint("user_id", "name"),)

    user_id: Mapped[UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    name: Mapped[str] = mapped_column(String(150))
    description: Mapped[str | None] = mapped_column(Text)
    is_public: Mapped[bool] = mapped_column(Boolean, default=False, server_default="false")

    user: Mapped[User] = relationship(back_populates="lists")
    items: Mapped[list[UserListItem]] = relationship(
        back_populates="user_list", cascade="all, delete-orphan"
    )


class UserListItem(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "user_list_items"
    __table_args__ = (UniqueConstraint("user_list_id", "media_id"),)

    user_list_id: Mapped[UUID] = mapped_column(
        ForeignKey("user_lists.id", ondelete="CASCADE"), index=True
    )
    media_id: Mapped[UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), index=True)
    position: Mapped[int] = mapped_column(Integer, default=0, server_default="0")
    notes: Mapped[str | None] = mapped_column(Text)

    user_list: Mapped[UserList] = relationship(back_populates="items")
    media: Mapped[Media] = relationship(back_populates="list_items")


class UserRating(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "user_ratings"
    __table_args__ = (
        UniqueConstraint("user_id", "media_id"),
        CheckConstraint("score >= 0 AND score <= 10", name="score_range"),
    )

    user_id: Mapped[UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    media_id: Mapped[UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), index=True)
    score: Mapped[float] = mapped_column(Float)

    user: Mapped[User] = relationship(back_populates="ratings")
    media: Mapped[Media] = relationship(back_populates="ratings")


class UserReview(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "user_reviews"
    __table_args__ = (UniqueConstraint("user_id", "media_id"),)

    user_id: Mapped[UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    media_id: Mapped[UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), index=True)
    title: Mapped[str | None] = mapped_column(String(200))
    body: Mapped[str] = mapped_column(Text)
    contains_spoilers: Mapped[bool] = mapped_column(Boolean, default=False, server_default="false")
    is_public: Mapped[bool] = mapped_column(Boolean, default=False, server_default="false")
    published_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    user: Mapped[User] = relationship(back_populates="reviews")
    media: Mapped[Media] = relationship(back_populates="reviews")


class UserIntegration(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "user_integrations"
    __table_args__ = (
        UniqueConstraint("user_id", "provider"),
        UniqueConstraint("provider", "external_user_id"),
    )

    user_id: Mapped[UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    provider: Mapped[str] = mapped_column(String(80), index=True)
    external_user_id: Mapped[str] = mapped_column(String(255))
    status: Mapped[IntegrationStatus] = mapped_column(String(30), index=True)
    scopes: Mapped[list[str]] = mapped_column(JSON, default=list)
    credentials_encrypted: Mapped[bytes | None] = mapped_column(LargeBinary)
    last_synced_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    user: Mapped[User] = relationship(back_populates="integrations")
    sync_jobs: Mapped[list[SyncJob]] = relationship(
        back_populates="integration", cascade="all, delete-orphan"
    )


class SyncJob(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "sync_jobs"
    __table_args__ = (
        CheckConstraint("attempts >= 0", name="attempts_non_negative"),
        CheckConstraint("max_attempts > 0", name="max_attempts_positive"),
        Index("ix_sync_jobs_status_run_after", "status", "run_after"),
    )

    user_id: Mapped[UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    user_integration_id: Mapped[UUID | None] = mapped_column(
        ForeignKey("user_integrations.id", ondelete="CASCADE"), index=True
    )
    job_type: Mapped[str] = mapped_column(String(80), index=True)
    status: Mapped[SyncJobStatus] = mapped_column(String(30), index=True)
    payload: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    result: Mapped[dict[str, Any] | None] = mapped_column(JSON)
    error: Mapped[str | None] = mapped_column(Text)
    attempts: Mapped[int] = mapped_column(Integer, default=0, server_default="0")
    max_attempts: Mapped[int] = mapped_column(Integer, default=3, server_default="3")
    run_after: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), index=True)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    integration: Mapped[UserIntegration | None] = relationship(back_populates="sync_jobs")
