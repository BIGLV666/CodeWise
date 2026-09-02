import base64
import os

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


class ApiKeyCrypto:
    """Encrypts API keys using the same AES-GCM format as the Java service."""

    iv_length = 12
    key_length = 32

    def __init__(self, master_key: str | None = None) -> None:
        encoded_key = master_key or os.getenv("API_KEY_MASTER_KEY")
        if not encoded_key:
            raise ValueError("API_KEY_MASTER_KEY is required")
        try:
            key = base64.b64decode(encoded_key, validate=True)
        except Exception as exc:
            raise ValueError("API_KEY_MASTER_KEY must be valid Base64") from exc
        if len(key) != self.key_length:
            raise ValueError("API_KEY_MASTER_KEY must decode to 32 bytes")
        self._key = key

    def encrypt(self, plain_api_key: str) -> str:
        if not plain_api_key or not plain_api_key.strip():
            raise ValueError("API key cannot be empty")
        iv = os.urandom(self.iv_length)
        ciphertext = AESGCM(self._key).encrypt(iv, plain_api_key.encode("utf-8"), None)
        return f"{self._encode(iv)}.{self._encode(ciphertext)}"

    def decrypt(self, encrypted_api_key: str) -> str:
        if not encrypted_api_key or not encrypted_api_key.strip():
            raise ValueError("Encrypted API key cannot be empty")
        parts = encrypted_api_key.split(".", 1)
        if len(parts) != 2:
            raise ValueError("Encrypted API key has an invalid format")
        try:
            iv = base64.b64decode(parts[0], validate=True)
            ciphertext = base64.b64decode(parts[1], validate=True)
            if len(iv) != self.iv_length:
                raise ValueError("Encrypted API key has an invalid IV")
            plain = AESGCM(self._key).decrypt(iv, ciphertext, None)
            return plain.decode("utf-8")
        except ValueError:
            raise
        except Exception as exc:
            raise ValueError("API key decryption failed") from exc

    @staticmethod
    def _encode(value: bytes) -> str:
        return base64.b64encode(value).decode("ascii")
