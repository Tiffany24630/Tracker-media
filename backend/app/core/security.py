from datetime import datetime, timedelta, timezone
from jose import jwt, JWTError
from passlib.context import CryptContext
import bcrypt
from app.core.config import get_settings
pwd=CryptContext(schemes=['pbkdf2_sha256'],deprecated='auto'); ALGO='HS256'
def hash_password(v): return pwd.hash(v)
def verify_password(v, h):
    if h.startswith(('$2a$', '$2b$', '$2y$')):
        # Legacy bcrypt hashes used the first 72 UTF-8 bytes.
        return bcrypt.checkpw(v.encode('utf-8')[:72], h.encode('ascii'))
    return pwd.verify(v, h)
def create_token(user_id):
    s=get_settings(); return jwt.encode({'sub':str(user_id),'exp':datetime.now(timezone.utc)+timedelta(minutes=s.access_token_expire_minutes)},s.jwt_secret,algorithm=ALGO)
def decode_token(t):
    try: return jwt.decode(t,get_settings().jwt_secret,algorithms=[ALGO])
    except JWTError: return None
