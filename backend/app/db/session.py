from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from app.core.config import get_settings
url=get_settings().database_url
if url.startswith('sqlite'):
    connect_args={'check_same_thread':False}
else: connect_args={}
engine=create_engine(url, future=True, pool_pre_ping=True, connect_args=connect_args)
SessionFactory=sessionmaker(engine, expire_on_commit=False, autoflush=False)
