from fastapi import Depends, HTTPException
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from sqlalchemy.orm import Session
from app.db.session import SessionFactory
from app.core.security import decode_token
from app.models import User
bearer=HTTPBearer(auto_error=False)
def db():
    s=SessionFactory()
    try: yield s
    finally: s.close()
def current_user(c:HTTPAuthorizationCredentials|None=Depends(bearer), s:Session=Depends(db)):
    if not c: raise HTTPException(401,'Authentication required')
    p=decode_token(c.credentials); u=s.get(User,p.get('sub')) if p and p.get('sub') else None
    if not u or not u.is_active: raise HTTPException(401,'Invalid or expired token')
    return u
