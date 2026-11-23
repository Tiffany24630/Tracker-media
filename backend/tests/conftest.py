import os
import tempfile
from pathlib import Path
_database_dir = tempfile.TemporaryDirectory()
os.environ['DATABASE_URL'] = f"sqlite:///{Path(_database_dir.name).as_posix()}/test.db"
os.environ['JWT_SECRET'] = 'test-secret-with-at-least-thirty-two-characters'
import pytest
from fastapi.testclient import TestClient
from app.db.base import Base
from app.db.session import engine
from app.main import app
@pytest.fixture
def client():
    with TestClient(app) as api_client:
        yield api_client
    Base.metadata.drop_all(engine)
    engine.dispose()
