from app.core.exceptions import NotFoundError
from app.recommendations.base import RecommendationStrategy
from app.recommendations.content_based import ContentBasedStrategy


class RecommendationRegistry:
    def __init__(self) -> None:
        self._strategies: dict[str, RecommendationStrategy] = {}
        self.register(ContentBasedStrategy())

    def register(self, strategy: RecommendationStrategy) -> None:
        self._strategies[strategy.name] = strategy

    def get(self, name: str) -> RecommendationStrategy:
        strategy = self._strategies.get(name.casefold())
        if strategy is None:
            raise NotFoundError(f"Recommendation strategy '{name}' not found")
        return strategy

    def all(self) -> list[RecommendationStrategy]:
        return list(self._strategies.values())


registry = RecommendationRegistry()


def get_recommendation_strategy(name: str) -> RecommendationStrategy:
    return registry.get(name)


def list_recommendation_strategies() -> list[RecommendationStrategy]:
    return registry.all()
