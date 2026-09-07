import logging
from typing import Any

from app.db.session import SessionFactory, engine

logger = logging.getLogger("app.worker")


async def startup(context: dict[str, Any]) -> None:
    context["session_factory"] = SessionFactory
    logger.info("worker_started")


async def shutdown(_context: dict[str, Any]) -> None:
    await engine.dispose()
    logger.info("worker_stopped")
