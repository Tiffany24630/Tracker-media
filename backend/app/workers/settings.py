from arq.connections import RedisSettings

from app.core.config import get_settings
from app.utils.logging import configure_logging
from app.workers.lifecycle import shutdown, startup
from app.workers.tasks import heartbeat

settings = get_settings()
configure_logging(settings.log_level)


class WorkerSettings:
    functions = [heartbeat]
    redis_settings = RedisSettings.from_dsn(str(settings.redis_url))
    on_startup = startup
    on_shutdown = shutdown
    health_check_interval = 30
