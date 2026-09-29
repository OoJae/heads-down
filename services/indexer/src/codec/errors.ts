export type DecodeErrorCode =
  | "TRUNCATED"
  | "TRAILING_BYTES"
  | "BAD_LENGTH"
  | "BAD_TAG"
  | "BAD_FIELD"
  | "BAD_ENCODING";

/** The only error a decoder may throw on attacker-controlled input. */
export class DecodeError extends Error {
  readonly code: DecodeErrorCode;
  constructor(code: DecodeErrorCode, message: string) {
    super(`${code}: ${message}`);
    this.name = "DecodeError";
    this.code = code;
  }
}
