from enum import StrEnum
class MediaType(StrEnum): MOVIE='movie'; SERIES='series'; ANIME='anime'; MANGA='manga'; MANHUA='manhua'; MANHWA='manhwa'; WEBTOON='webtoon'; BOOK='book'; NOVEL='novel'; MUSIC='music'; ALBUM='album'; OTHER='other'
class MediaStatus(StrEnum): UNKNOWN='unknown'; FINISHED='finished'; RELEASING='releasing'; UPCOMING='upcoming'; CANCELLED='cancelled'
class MediaTitleType(StrEnum): PRIMARY='primary'; ORIGINAL='original'; ALTERNATIVE='alternative'
class MediaUnitType(StrEnum): SEASON='season'; EPISODE='episode'; VOLUME='volume'; CHAPTER='chapter'; TRACK='track'
class TrackingStatus(StrEnum): PLANNED='planned'; IN_PROGRESS='in_progress'; COMPLETED='completed'; ON_HOLD='on_hold'; DROPPED='dropped'
class TrackingSource(StrEnum): MANUAL='manual'; IMPORT='import'; ANILIST='anilist'; TRAKT='trakt'; SPOTIFY='spotify'; OTHER='other'
