"""Safe application errors."""


class RelayError(Exception):
    """An expected error safe to represent through the public API."""

    def __init__(self, http_status: int, status: str, message: str) -> None:
        super().__init__(message)
        self.http_status = http_status
        self.status = status
        self.message = message


def invalid_argument(message: str) -> RelayError:
    return RelayError(400, "INVALID_ARGUMENT", message)


def unauthenticated() -> RelayError:
    return RelayError(401, "UNAUTHENTICATED", "Authentication is required")


def permission_denied() -> RelayError:
    return RelayError(403, "PERMISSION_DENIED", "Request authentication is invalid")


def not_found(resource: str) -> RelayError:
    return RelayError(404, "NOT_FOUND", f"{resource} was not found")


def failed_precondition(message: str) -> RelayError:
    return RelayError(400, "FAILED_PRECONDITION", message)


def resource_exhausted() -> RelayError:
    return RelayError(429, "RESOURCE_EXHAUSTED", "The relay is temporarily overloaded")
