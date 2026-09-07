from __future__ import annotations

from datetime import datetime
from typing import TYPE_CHECKING, Any
from uuid import UUID

from sqlalchemy import JSON, DateTime, Float, ForeignKey, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, TimestampMixin, UUIDPrimaryKeyMixin

if TYPE_CHECKING:
    from app.models.media import Media
    from app.models.user import User


class MediaMatchDecision(UUIDPrimaryKeyMixin, TimestampMixin, Base):
    __tablename__ = "media_match_decisions"
    __table_args__ = (UniqueConstraint("provider", "external_id", "candidate_media_id"),)

    provider: Mapped[str] = mapped_column(String(80), index=True)
    external_id: Mapped[str] = mapped_column(String(255), index=True)
    candidate_media_id: Mapped[UUID] = mapped_column(
        ForeignKey("media.id", ondelete="CASCADE"), index=True
    )
    status: Mapped[str] = mapped_column(String(20), index=True)
    confidence: Mapped[float] = mapped_column(Float)
    confidence_level: Mapped[str] = mapped_column(String(20))
    evidence: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    reviewed_by_user_id: Mapped[UUID | None] = mapped_column(
        ForeignKey("users.id", ondelete="SET NULL"), index=True
    )
    reviewed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    candidate_media: Mapped[Media] = relationship()
    reviewed_by: Mapped[User | None] = relationship()
