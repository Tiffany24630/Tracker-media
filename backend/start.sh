#!/bin/sh
set -e
export PYTHONPATH=/app

alembic upgrade head
exec uvicorn app.main:app --host 0.0.0.0 --port "${PORT:-8000}"
