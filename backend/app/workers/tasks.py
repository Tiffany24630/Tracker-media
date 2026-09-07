from datetime import UTC, datetime
from typing import Any


async def heartbeat(_context: dict[str, Any]) -> str:
    """Small operational job used to validate worker availability."""
    return datetime.now(UTC).isoformat()
