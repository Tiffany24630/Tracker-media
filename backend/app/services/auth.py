from datetime import UTC, datetime, timedelta
from hmac import compare_digest
from uuid import UUID, uuid4

from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.exceptions import AuthenticationError, ConflictError
from app.core.config import get_settings
from app.core.security import (
    create_access_token,
    create_opaque_token,
    hash_password,
    hash_token,
    opaque_token_id,
    verify_password,
)
from app.models.auth import AuthSession, EmailVerificationToken
from app.models.user import User
from app.schemas.auth import RegisterRequest, TokenResponse


def utc_now() -> datetime:
    return datetime.now(UTC)


def is_expired(value: datetime) -> bool:
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value <= utc_now()


async def register_user(session: AsyncSession, payload: RegisterRequest) -> User:
    user = User(
        email=str(payload.email).lower(),
        display_name=payload.display_name,
        password_hash=hash_password(payload.password),
    )
    session.add(user)
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        raise ConflictError("An account with this email already exists") from None
    await session.refresh(user)
    return user


async def authenticate_user(session: AsyncSession, email: str, password: str) -> User:
    user = await session.scalar(select(User).where(User.email == email.lower()))
    if user is None or not user.is_active or not verify_password(password, user.password_hash):
        raise AuthenticationError("Invalid email or password")
    user.last_login_at = utc_now()
    await session.commit()
    await session.refresh(user)
    return user


async def issue_token_pair(
    session: AsyncSession,
    user: User,
    *,
    ip_address: str | None,
    user_agent: str | None,
) -> TokenResponse:
    settings = get_settings()
    auth_session = AuthSession(
        id=uuid4(),
        user_id=user.id,
        refresh_token_hash="",
        expires_at=utc_now() + timedelta(days=settings.refresh_token_expire_days),
        created_by_ip=ip_address,
        user_agent=user_agent[:500] if user_agent else None,
    )
    refresh_token = create_opaque_token(auth_session.id)
    auth_session.refresh_token_hash = hash_token(refresh_token)
    session.add(auth_session)
    await session.commit()
    return TokenResponse(
        access_token=create_access_token(user.id, auth_session.id, user.token_version),
        refresh_token=refresh_token,
        expires_in=settings.access_token_expire_minutes * 60,
    )


async def rotate_refresh_token(session: AsyncSession, refresh_token: str) -> TokenResponse:
    try:
        session_id = opaque_token_id(refresh_token)
    except ValueError:
        raise AuthenticationError("Invalid refresh token") from None
    auth_session = await session.get(AuthSession, session_id)
    if (
        auth_session is None
        or auth_session.revoked_at is not None
        or is_expired(auth_session.expires_at)
        or not compare_digest(auth_session.refresh_token_hash, hash_token(refresh_token))
    ):
        raise AuthenticationError("Invalid or expired refresh token")
    user = await session.get(User, auth_session.user_id)
    if user is None or not user.is_active:
        raise AuthenticationError()
    rotated_token = create_opaque_token(auth_session.id)
    auth_session.refresh_token_hash = hash_token(rotated_token)
    auth_session.last_used_at = utc_now()
    await session.commit()
    settings = get_settings()
    return TokenResponse(
        access_token=create_access_token(user.id, auth_session.id, user.token_version),
        refresh_token=rotated_token,
        expires_in=settings.access_token_expire_minutes * 60,
    )


async def revoke_session(session: AsyncSession, session_id: UUID) -> None:
    auth_session = await session.get(AuthSession, session_id)
    if auth_session is not None and auth_session.revoked_at is None:
        auth_session.revoked_at = utc_now()
        await session.commit()


async def change_password(
    session: AsyncSession,
    user: User,
    *,
    current_password: str,
    new_password: str,
    ip_address: str | None,
    user_agent: str | None,
) -> TokenResponse:
    if not verify_password(current_password, user.password_hash):
        raise AuthenticationError("Current password is incorrect")
    user.password_hash = hash_password(new_password)
    user.password_changed_at = utc_now()
    user.token_version += 1
    await session.execute(
        update(AuthSession)
        .where(AuthSession.user_id == user.id, AuthSession.revoked_at.is_(None))
        .values(revoked_at=utc_now())
    )
    await session.commit()
    await session.refresh(user)
    return await issue_token_pair(
        session, user, ip_address=ip_address, user_agent=user_agent
    )


async def issue_email_verification_token(session: AsyncSession, user: User) -> str:
    settings = get_settings()
    token_record = EmailVerificationToken(
        id=uuid4(),
        user_id=user.id,
        token_hash="",
        expires_at=utc_now() + timedelta(minutes=settings.email_verification_expire_minutes),
    )
    token = create_opaque_token(token_record.id)
    token_record.token_hash = hash_token(token)
    session.add(token_record)
    await session.commit()
    return token


async def verify_email_token(session: AsyncSession, token: str) -> User:
    try:
        token_id = opaque_token_id(token)
    except ValueError:
        raise AuthenticationError("Invalid email verification token") from None
    record = await session.get(EmailVerificationToken, token_id)
    if (
        record is None
        or record.consumed_at is not None
        or is_expired(record.expires_at)
        or not compare_digest(record.token_hash, hash_token(token))
    ):
        raise AuthenticationError("Invalid or expired email verification token")
    user = await session.get(User, record.user_id)
    if user is None or not user.is_active:
        raise AuthenticationError()
    now = utc_now()
    user.email_verified_at = now
    record.consumed_at = now
    await session.commit()
    await session.refresh(user)
    return user
