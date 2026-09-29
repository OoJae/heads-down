//! API errors: a status, a stable machine code, and a fixed human message. Messages never echo
//! request content (no tokens, messages, addresses or certificates).

use axum::extract::rejection::JsonRejection;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Serialize;

use crate::attest::AttestError;
use crate::nonce::{ConsumeError, IssueError, StoreError};
use crate::session::SessionError;
use crate::siws::SiwsError;

#[derive(Debug)]
pub struct ApiError {
    pub status: StatusCode,
    pub code: &'static str,
    pub message: String,
}

#[derive(Serialize)]
struct Body<'a> {
    error: &'a str,
    message: &'a str,
}

impl ApiError {
    pub fn new(status: StatusCode, code: &'static str, message: impl Into<String>) -> Self {
        Self { status, code, message: message.into() }
    }

    pub fn bad_request(code: &'static str, message: &str) -> Self {
        Self::new(StatusCode::BAD_REQUEST, code, message)
    }

    pub fn internal() -> Self {
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, "internal", "internal error")
    }

    pub fn unavailable(code: &'static str, message: &str) -> Self {
        Self::new(StatusCode::SERVICE_UNAVAILABLE, code, message)
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        if self.status.is_server_error() {
            tracing::warn!(code = self.code, status = self.status.as_u16(), "request failed");
        } else {
            tracing::info!(code = self.code, status = self.status.as_u16(), "request rejected");
        }
        (self.status, Json(Body { error: self.code, message: &self.message })).into_response()
    }
}

impl From<JsonRejection> for ApiError {
    fn from(r: JsonRejection) -> Self {
        if r.status() == StatusCode::PAYLOAD_TOO_LARGE {
            Self::new(StatusCode::PAYLOAD_TOO_LARGE, "payload_too_large", "request body too large")
        } else if r.status() == StatusCode::UNSUPPORTED_MEDIA_TYPE {
            Self::new(StatusCode::UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "expected application/json")
        } else {
            Self::bad_request("invalid_json", "request body is not valid JSON for this endpoint")
        }
    }
}

impl From<SiwsError> for ApiError {
    fn from(e: SiwsError) -> Self {
        let status = match e {
            SiwsError::TooLarge => StatusCode::PAYLOAD_TOO_LARGE,
            SiwsError::NotUtf8 | SiwsError::Parse(_) | SiwsError::BadSignatureEncoding => StatusCode::BAD_REQUEST,
            _ => StatusCode::UNAUTHORIZED,
        };
        Self::new(status, e.code(), e.to_string())
    }
}

impl From<SessionError> for ApiError {
    fn from(e: SessionError) -> Self {
        let code = match e {
            SessionError::Expired => "session_expired",
            _ => "session_invalid",
        };
        Self::new(StatusCode::UNAUTHORIZED, code, "missing, invalid or expired session token")
    }
}

impl From<StoreError> for ApiError {
    fn from(e: StoreError) -> Self {
        match e {
            StoreError::Full => Self::unavailable("nonce_capacity", "too many outstanding nonces; retry later"),
            StoreError::Collision | StoreError::Backend(_) => {
                tracing::error!(error = %e, "nonce store failure");
                Self::internal()
            }
        }
    }
}

impl From<IssueError> for ApiError {
    fn from(e: IssueError) -> Self {
        match e {
            IssueError::Store(s) => s.into(),
            IssueError::Random(_) | IssueError::Clock => {
                tracing::error!(error = %e, "nonce issue failure");
                Self::internal()
            }
        }
    }
}

impl From<ConsumeError> for ApiError {
    fn from(e: ConsumeError) -> Self {
        match e {
            ConsumeError::NotFound => Self::new(
                StatusCode::UNAUTHORIZED,
                "nonce_unknown_or_used",
                "nonce was never issued or was already used",
            ),
            ConsumeError::Expired => Self::new(StatusCode::UNAUTHORIZED, "nonce_expired", "nonce expired"),
            ConsumeError::WrongSubject => {
                Self::new(StatusCode::FORBIDDEN, "nonce_wrong_subject", "nonce was issued to another authority")
            }
            ConsumeError::Store(s) => s.into(),
        }
    }
}

impl From<AttestError> for ApiError {
    fn from(e: AttestError) -> Self {
        Self::new(StatusCode::UNPROCESSABLE_ENTITY, e.code(), e.to_string())
    }
}
