"""Router dei modelli: astrazione provider + fail-over + budget di latenza.

Regola architetturale: ogni strato del cervello dichiara un BUDGET in ms.
- L2 (limbico): < 90 ms, preferibilmente modello locale piccolo.
- L3 (corteccia): < 2 s, modello grande (cloud o locale potente).

Quando il provider scelto non risponde entro il budget, il router degrada al
provider successivo della catena e LOGGA il degrado: un pianificatore che
mentisce sulla sua lentezza è peggio di uno lento.
"""

from __future__ import annotations

import json
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field


@dataclass(frozen=True)
class ModelReply:
    text: str
    provider: str
    latency_ms: float
    degraded: bool = False
    error: str | None = None


class ModelProvider:
    """Interfaccia: completamento sincrono con timeout esplicito."""

    name = "base"
    default_latency_ms = 1000.0

    def complete(self, prompt: str, budget_ms: float) -> ModelReply:
        raise NotImplementedError


class LocalProvider(ModelProvider):
    """Modello locale deterministico (placeholder sostituibile).

    Finché Qwen3.5/Gemma/GPT4All non è installato, questo provider esegue
    una classificazione REALE a parole-chiave + template: nessuna rete,
    latenza trascurabile, comportamento deterministico (testabile).
    """

    name = "local-echo"

    # Le regole sono applicate ALL'INTENTO, non all'intero prompt: il prompt
    # di sistema contiene parole come "Rispondi", "azione", "look" e se fosse
    # classificato per intero ogni richiesta darebbe la stessa risposta
    # (difetto: "vai verso il cubo" diventava "dico hello").
    RULES = (
        (("saluta", "ciao", "buongiorno", "hello"), "greeting"),
        (("paura", "pericolo", "aiuto", "ferma"), "fear"),
        (("sorridi", "sorride", "ride", "faccina"), "smile"),
        (("vai", "vieni", "avvicin", "afferra", "prendi", "raggiungi"), "goal"),
        (("gira", "guarda", "orienta", "verso", "destra", "sinistra"), "orient"),
        (("bravo", "bene", "grazie"), "praise"),
    )

    # L'intento e' la riga marcata nel prompt; se il provider viene usato
    # come completamento libero si classifica il testo intero.
    INTENT_RE = re.compile(r"Intento:\s*(.+)", re.IGNORECASE)

    def extract_intent(self, prompt: str) -> str:
        m = self.INTENT_RE.search(prompt)
        if m:
            return m.group(1).strip()
        return prompt

    def classify(self, prompt: str) -> str:
        p = self.extract_intent(prompt).lower()
        for words, tag in self.RULES:
            if any(w in p for w in words):
                return tag
        return "idle"

    def complete(self, prompt: str, budget_ms: float) -> ModelReply:
        t0 = time.monotonic()
        tag = self.classify(prompt)
        # Le template rispettano il CONTRATTO del pianificatore:
        # una riga `azione|parametro`. Il testo in prosa resta per
        # l'intento "idle", che non ha azione fisica da eseguire.
        templates = {
            "greeting": "speak|hello",
            "fear": "face|worried",
            "smile": "face|happy",
            "orient": "look|",
            "goal": "goto|",
            "praise": "face|happy",
            "idle": "Resto in attesa, osservando l'ambiente.",
        }
        lat = (time.monotonic() - t0) * 1000.0
        return ModelReply(text=templates[tag], provider=self.name,
                          latency_ms=lat)


class CloudProvider(ModelProvider):
    """Provider HTTP OpenAI-compat (Groq, Cerebras, OpenRouter, Mistral...).

    Config via env: COZMO_LLM_BASE_URL, COZMO_LLM_API_KEY, COZMO_LLM_MODEL.
    Se la chiave manca resta INERTE (solleva) e il router degrada al locale:
    il robot non deve bloccarsi per una rete assente.
    """

    name = "cloud"
    default_latency_ms = 900.0

    def __init__(self):
        self.base = os.environ.get("COZMO_LLM_BASE_URL", "").rstrip("/")
        self.key = os.environ.get("COZMO_LLM_API_KEY", "")
        self.model = os.environ.get("COZMO_LLM_MODEL", "llama-3.3-70b-versatile")

    def available(self) -> bool:
        return bool(self.base and self.key)

    def complete(self, prompt: str, budget_ms: float) -> ModelReply:
        if not self.available():
            raise RuntimeError("cloud non configurato")
        body = json.dumps({
            "model": self.model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 220,
            "temperature": 0.4,
        }).encode()
        req = urllib.request.Request(
            self.base + "/chat/completions", data=body,
            headers={"Content-Type": "application/json",
                     "Authorization": f"Bearer {self.key}"})
        t0 = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=budget_ms / 1000.0) as resp:
                data = json.loads(resp.read().decode())
            text = data["choices"][0]["message"]["content"].strip()
            return ModelReply(text=text, provider=f"{self.name}:{self.model}",
                              latency_ms=(time.monotonic() - t0) * 1000.0)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError,
                KeyError, IndexError) as e:
            raise RuntimeError(f"cloud: {e}") from e


class GroqProvider(CloudProvider):
    name = "groq"


class GeminiProvider(ModelProvider):
    """Google Gemini REST (text-only). Stessa semantica di fail-over."""

    name = "gemini"
    default_latency_ms = 1200.0

    def __init__(self):
        self.key = os.environ.get("GOOGLE_API_KEY", "") or os.environ.get("GEMINI_API_KEY", "")
        self.model = os.environ.get("GEMINI_MODEL", "gemini-2.0-flash")

    def available(self) -> bool:
        return bool(self.key)

    def complete(self, prompt: str, budget_ms: float) -> ModelReply:
        if not self.available():
            raise RuntimeError("gemini non configurato")
        url = (f"https://generativelanguage.googleapis.com/v1beta/models/"
               f"{self.model}:generateContent?key={self.key}")
        body = json.dumps({"contents": [{"parts": [{"text": prompt}]}]}).encode()
        req = urllib.request.Request(url, data=body,
                                     headers={"Content-Type": "application/json"})
        t0 = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=budget_ms / 1000.0) as resp:
                data = json.loads(resp.read().decode())
            text = data["candidates"][0]["content"]["parts"][0]["text"].strip()
            return ModelReply(text=text, provider=f"{self.name}:{self.model}",
                              latency_ms=(time.monotonic() - t0) * 1000.0)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError,
                KeyError, IndexError) as e:
            raise RuntimeError(f"gemini: {e}") from e


class ModelRouter:
    """Catena di provider con degrado ordinato e log delle decisioni."""

    def __init__(self, chain: list[ModelProvider] | None = None):
        self.chain: list[ModelProvider] = chain if chain is not None else [
            GroqProvider(), GeminiProvider(), LocalProvider()]
        self.log: list[dict] = []

    def route(self, prompt: str, budget_ms: float,
              prefer: str | None = None) -> ModelReply:
        """Prova i provider nell'ordine; a pareggio con 'prefer' va primo."""
        ordered = list(self.chain)
        if prefer:
            ordered.sort(key=lambda p: 0 if p.name == prefer else 1)
        last_err: Exception | None = None
        for provider in ordered:
            try:
                reply = provider.complete(prompt, budget_ms)
                if reply.latency_ms > budget_ms:
                    raise RuntimeError(f"{provider.name} fuori budget")
                self.log.append({"provider": reply.provider, "ms": reply.latency_ms,
                                 "degraded": False})
                return reply
            except Exception as e:  # degrado esplicito, mai silenzioso
                last_err = e
                self.log.append({"provider": provider.name, "error": str(e),
                                 "degraded": True})
        return ModelReply(text="", provider="none", latency_ms=0.0,
                          degraded=True, error=str(last_err))

    def stats(self) -> dict:
        calls = len(self.log)
        failures = sum(1 for l in self.log if l.get("error"))
        return {"calls": calls, "failures": failures,
                "chain": [p.name for p in self.chain]}