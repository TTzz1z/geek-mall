package com.geekmall.ai;

public enum RiskLevel {
    L0, L1, L2, L3, L4;

    public boolean requiresLogin() {
        return this != L0;
    }

    public boolean requiresConfirmation() {
        return this == L3;
    }

    public boolean forbidden() {
        return this == L4;
    }
}
