from abc import ABC, abstractmethod


class EmailVerificationProvider(ABC):
    """Delivery boundary for a future transactional email provider."""

    @abstractmethod
    async def send_verification(self, *, email: str, token: str) -> None:
        raise NotImplementedError
