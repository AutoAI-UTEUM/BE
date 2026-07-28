"""Validated environment configuration."""

from enum import StrEnum

from pydantic import BaseModel, ConfigDict, Field, PositiveInt, SecretStr
from pydantic.alias_generators import to_camel
from pydantic_settings import BaseSettings, SettingsConfigDict


class ReasoningEffort(StrEnum):
    """Supported Grok reasoning effort values used by agent profiles."""

    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


class AgentLlmProfile(BaseModel):
    """DEC-002 D5 provider configuration for one agent role."""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )

    model: str = Field(min_length=1)
    reasoning_effort: ReasoningEffort
    max_tokens: PositiveInt
    temperature: float | None = None


class Settings(BaseSettings):
    """App configuration loaded once by ``create_app``."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=True,
        extra="ignore",
        populate_by_name=True,
    )

    edupilot_internal_token: SecretStr = Field(
        validation_alias="EDUPILOT_INTERNAL_TOKEN",
        min_length=1,
    )
    xai_api_key: SecretStr = Field(
        validation_alias="XAI_API_KEY",
        min_length=1,
    )
    model_name: str = Field(
        default="grok-4.5",
        validation_alias="MODEL_NAME",
        min_length=1,
    )

    turn_timeout_seconds: PositiveInt = Field(
        default=180,
        validation_alias="TURN_TIMEOUT_SECONDS",
    )
    turn_first_event_timeout_seconds: PositiveInt = Field(
        default=30,
        validation_alias="TURN_FIRST_EVENT_TIMEOUT_SECONDS",
    )
    grade_timeout_seconds: PositiveInt = Field(
        default=90,
        validation_alias="GRADE_TIMEOUT_SECONDS",
    )
    quiz_assessment_timeout_seconds: PositiveInt = Field(
        default=45,
        validation_alias="QUIZ_ASSESSMENT_TIMEOUT_SECONDS",
    )
    diagnosis_timeout_seconds: PositiveInt = Field(
        default=45,
        validation_alias="DIAGNOSIS_TIMEOUT_SECONDS",
    )
    extract_timeout_seconds: PositiveInt = Field(
        default=120,
        validation_alias="EXTRACT_TIMEOUT_SECONDS",
    )
    edupilot_upload_max_mb: int = Field(
        default=45,
        ge=1,
        le=45,
        validation_alias="EDUPILOT_UPLOAD_MAX_MB",
    )
    edupilot_extract_max_pages: int = Field(
        default=300,
        ge=1,
        le=300,
        validation_alias="EDUPILOT_EXTRACT_MAX_PAGES",
    )

    agent_reasoning_effort: ReasoningEffort = Field(
        default=ReasoningEffort.MEDIUM,
        validation_alias="AGENT_REASONING_EFFORT",
    )
    agent_max_tokens: PositiveInt = Field(
        default=16_384,
        validation_alias="AGENT_MAX_TOKENS",
    )
    agent_temperature: float | None = Field(
        default=None,
        validation_alias="AGENT_TEMPERATURE",
    )
    orchestrator_reasoning_effort: ReasoningEffort = Field(
        default=ReasoningEffort.LOW,
        validation_alias="ORCHESTRATOR_REASONING_EFFORT",
    )
    explainer_reasoning_effort: ReasoningEffort = Field(
        default=ReasoningEffort.MEDIUM,
        validation_alias="EXPLAINER_REASONING_EFFORT",
    )
    qa_reasoning_effort: ReasoningEffort = Field(
        default=ReasoningEffort.MEDIUM,
        validation_alias="QA_REASONING_EFFORT",
    )

    @property
    def agent_llm_profile(self) -> AgentLlmProfile:
        """Build the default DEC-002 profile without exposing secrets."""
        return AgentLlmProfile(
            model=self.model_name,
            reasoning_effort=self.agent_reasoning_effort,
            max_tokens=self.agent_max_tokens,
            temperature=self.agent_temperature,
        )

    def _profile(self, effort: ReasoningEffort) -> AgentLlmProfile:
        return AgentLlmProfile(
            model=self.model_name,
            reasoning_effort=effort,
            max_tokens=self.agent_max_tokens,
            temperature=self.agent_temperature,
        )

    @property
    def orchestrator_llm_profile(self) -> AgentLlmProfile:
        """Build the low-latency Plan profile."""
        return self._profile(self.orchestrator_reasoning_effort)

    @property
    def explainer_llm_profile(self) -> AgentLlmProfile:
        """Build the interactive explanation profile."""
        return self._profile(self.explainer_reasoning_effort)

    @property
    def qa_llm_profile(self) -> AgentLlmProfile:
        """Build the interactive question-answering profile."""
        return self._profile(self.qa_reasoning_effort)

    @property
    def upload_max_bytes(self) -> int:
        """Return the configured multipart limit in bytes."""
        return self.edupilot_upload_max_mb * 1024 * 1024
