from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from app.core.config import get_settings

url = get_settings().database_url

connect_args = {'check_same_thread': False} if url.startswith('sqlite') else {}
# Never switch databases after a transient DNS/connection failure.
engine = create_engine(url, future=True, pool_pre_ping=True, connect_args=connect_args)

SessionFactory = sessionmaker(engine, expire_on_commit=False, autoflush=False)
