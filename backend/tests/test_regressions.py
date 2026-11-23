from unittest.mock import AsyncMock, patch
import bcrypt
from app.core.security import hash_password, verify_password
from app.main import _passes_genres
from app.providers.simple import _book_query
from app.providers.simple import normalize_status


def test_passwords():
    password = 'á' * 80
    assert verify_password(password, hash_password(password))
    legacy = bcrypt.hashpw(b'old-password', bcrypt.gensalt()).decode()
    assert verify_password('old-password', legacy)
    assert not verify_password('wrong-password', legacy)


def test_auth_and_catalog(client):
    payload = dict(email='regression@example.com', display_name='Regression', password='test-password-123')
    response = client.post('/api/v1/auth/register', json=payload)
    assert response.status_code == 200, response.text
    login = client.post('/api/v1/auth/login', json=payload)
    assert login.status_code == 200, login.text
    headers = {'Authorization': f"Bearer {login.json()['access_token']}"}
    for path in ('/auth/me', '/library', '/media', '/notifications'):
        response = client.get('/api/v1' + path, headers=headers)
        assert response.status_code == 200, response.text
    assert client.get('/api/v1/auth/me').status_code == 401
    assert client.post('/api/v1/auth/login', json={**payload, 'password': 'incorrect'}).status_code == 401
    with patch('app.main.discover_top_content', new=AsyncMock(return_value=[])):
        response = client.get('/api/v1/recommendations?limit=9', headers=headers)
        assert response.status_code == 200, response.text


def test_year_filter_reaches_book_provider(client):
    account = client.post('/api/v1/auth/register', json=dict(
        email='books@example.com', display_name='Books', password='test-password-123'))
    headers = {'Authorization': f"Bearer {account.json()['access_token']}"}
    data = [dict(source='openlibrary', external_id='new', media_type='book', title='New book',
                 release_year=2026, genres=['Fantasy']),
            dict(source='openlibrary', external_id='old', media_type='book', title='Old book',
                 release_year=2025, genres=['Fantasy'])]
    with patch('app.main.search_openlibrary', new=AsyncMock(return_value=data)) as provider:
        response = client.get('/api/v1/search?query=&media_type=book&year=2026&include_genres=Fantasía', headers=headers)
        assert response.status_code == 200, response.text
        assert [x['title'] for x in response.json()] == ['New book']
        assert provider.call_args.args[2] == [2026]
    assert 'first_publish_year:(2026)' in _book_query('', [2026])


def test_genres():
    assert _passes_genres(['Action'], ['Acción'], [])
    assert not _passes_genres(['Action', 'Horror'], ['Acción'], ['Terror'])
    assert not _passes_genres([''], ['Acción'], [])


def test_statuses_and_notification_uuid():
    from uuid import uuid4
    from datetime import datetime, timezone
    from app.schemas import NotificationOut
    assert normalize_status('NOT_YET_RELEASED') == 'upcoming'
    assert normalize_status('Cancelled') == 'cancelled'
    notification = NotificationOut(id=uuid4(), title='Release', message='New release',
                                  media_id=uuid4(), is_read=False, created_at=datetime.now(timezone.utc))
    assert isinstance(notification.model_dump(mode='json')['media_id'], str)


def test_comic_year_and_excluded_genre(client):
    account = client.post('/api/v1/auth/register', json=dict(
        email='comics@example.com', display_name='Comics', password='test-password-123'))
    headers = {'Authorization': f"Bearer {account.json()['access_token']}"}
    data = [dict(source='openlibrary', external_id='comic-new', media_type='comic', title='Batman',
                 release_year=2026, genres=['Action']),
            dict(source='openlibrary', external_id='comic-horror', media_type='comic', title='Horror',
                 release_year=2026, genres=['Action', 'Horror'])]
    with patch('app.main.search_comics', new=AsyncMock(return_value=data)) as provider:
        response = client.get('/api/v1/search?query=&media_type=comic&year=2026&exclude_genres=Terror', headers=headers)
        assert response.status_code == 200, response.text
        assert [x['title'] for x in response.json()] == ['Batman']
        assert provider.call_args.args[2] == [2026]
