import os
DEBUG = False
ADDIN_NAME = os.path.basename(os.path.dirname(__file__))
COMPANY_NAME = "Volumind"
PALETTE_ID = "VolumindAI_MainPalette"
CUSTOM_EVENT_ID = "VolumindAI_OllamaResult"
DEFAULT_MODEL = "qwen2.5-coder:3b"
DEFAULT_VISION_MODEL = "qwen2.5vl:3b"
OLLAMA_URL = "http://127.0.0.1:11434/api/chat"
