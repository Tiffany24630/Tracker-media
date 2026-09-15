from datetime import date, datetime
from pydantic import BaseModel, ConfigDict, EmailStr, Field, HttpUrl
from typing import Any
from app.models.enums import *
class Register(BaseModel): email:EmailStr; display_name:str=Field(min_length=1,max_length=100); password:str=Field(min_length=8,max_length=128)
class Login(BaseModel): email:EmailStr; password:str
class Token(BaseModel): access_token:str; token_type:str='bearer'
class UserOut(BaseModel): model_config=ConfigDict(from_attributes=True); id:str; email:EmailStr; display_name:str
class ExternalIdIn(BaseModel): provider:str; external_id:str; url:HttpUrl|None=None
class MediaCreate(BaseModel): title:str=Field(min_length=1,max_length=500); media_type:MediaType; description:str|None=None; release_year:int|None=None; release_date:date|None=None; status:MediaStatus=MediaStatus.UNKNOWN; original_language:str|None=None; cover_url:HttpUrl|None=None; genres:list[str]=[]; aliases:list[str]=[]; external_ids:list[ExternalIdIn]=[]; metadata:dict[str,Any]={}
class MediaOut(BaseModel): id:str; media_type:str; title:str; description:str|None; release_year:int|None; status:str; original_language:str|None; cover_url:str|None; genres:list[str]; external_ids:list[dict]; metadata:dict
class SearchResult(BaseModel): source:str; external_id:str; media_type:str; title:str; description:str|None=None; release_year:int|None=None; cover_url:str|None=None
class LibraryUpsert(BaseModel): status:TrackingStatus=TrackingStatus.PLANNED; progress:float=Field(default=0,ge=0); total:float|None=Field(default=None,ge=0); rating:float|None=Field(default=None,ge=0,le=10); notes:str|None=None; source:TrackingSource=TrackingSource.MANUAL
class LibraryOut(BaseModel): id:str; media_id:str; status:str; progress:float; total:float|None; rating:float|None; notes:str|None; source:str; media:MediaOut
class ListCreate(BaseModel): name:str=Field(min_length=1,max_length=150); description:str|None=None
class ProgressIn(BaseModel): value:float=Field(ge=0); unit:MediaUnitType|str='episode'; source:TrackingSource=TrackingSource.MANUAL
