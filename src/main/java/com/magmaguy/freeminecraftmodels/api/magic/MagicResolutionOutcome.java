package com.magmaguy.freeminecraftmodels.api.magic;

/** Observable result of routing an impact through the one-shot resolver contract. */
public enum MagicResolutionOutcome {
    APPLIED,
    NO_DAMAGE,
    STANDALONE_FALLBACK,
    FAILED
}
