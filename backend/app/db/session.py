import logging
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from app.core.config import get_settings, DEFAULT_SQLITE_PATH

logger = logging.getLogger("app.db.session")
url = get_settings().database_url

try:
    if url.startswith('sqlite'):
        connect_args = {'check_same_thread': False}
    else:
        connect_args = {}
    engine = create_engine(url, future=True, pool_pre_ping=True, connect_args=connect_args)
    # Check if driver is available
    with engine.connect():
        pass
except Exception as e:
    fallback_url = f'sqlite:///{DEFAULT_SQLITE_PATH}'
    logger.warning("No se pudo conectar a la base de datos configurada (%s): %s. Usando SQLite local: %s", url, e, fallback_url)
    engine = create_engine(fallback_url, future=True, pool_pre_ping=True, connect_args={'check_same_thread': False})

SessionFactory = sessionmaker(engine, expire_on_commit=False, autoflush=False)
