class AppError(Exception):
    status_code = 400
    code = 'app_error'
    def __init__(self, message: str, status_code: int | None = None):
        super().__init__(message); self.message = message
        if status_code: self.status_code = status_code
class AuthenticationError(AppError):
    status_code=401; code='authentication_error'
class ConflictError(AppError):
    status_code=409; code='conflict'
class NotFoundError(AppError):
    status_code=404; code='not_found'
class ExternalProviderError(AppError):
    status_code=502; code='provider_error'
class ProviderConfigurationError(AppError):
    status_code=503; code='provider_not_configured'
