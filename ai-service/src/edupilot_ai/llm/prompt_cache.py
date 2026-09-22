"""Lossless stable-prefix layout for explicitly opted-in JSON user prompts."""

import json
from collections.abc import Sequence

from edupilot_ai.llm.bridge import LlmMessage, LlmPromptCache


def stable_prefix_messages(
    messages: Sequence[LlmMessage], cache: LlmPromptCache
) -> Sequence[LlmMessage]:
    """Split one JSON object into disjoint static/dynamic user messages.

    Do not reinterpret arbitrary chat histories or multimodal inputs. All values,
    nulls, list ordering and system instructions survive; nothing is summarized.
    """
    if (
        len(messages) != 2
        or messages[0].get("role") != "system"
        or messages[1].get("role") != "user"
        or not isinstance(content := messages[1].get("content"), str)
    ):
        return messages
    try:
        payload = json.loads(content)
    except ValueError:
        return messages
    if not isinstance(payload, dict):
        return messages
    stable = {key: payload[key] for key in cache.stable_user_fields if key in payload}
    if not stable:
        return messages
    dynamic = {key: value for key, value in payload.items() if key not in stable}
    return [
        messages[0],
        {**messages[1], "content": json.dumps(stable, ensure_ascii=False, separators=(",", ":"))},
        {**messages[1], "content": json.dumps(dynamic, ensure_ascii=False, separators=(",", ":"))},
    ]
