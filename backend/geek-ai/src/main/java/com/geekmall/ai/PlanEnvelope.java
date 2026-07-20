package com.geekmall.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** LLM planner output after validation. */
public record PlanEnvelope(
        String agent,
        double confidence,
        String userGoal,
        Map<String, Object> slots,
        List<PlannedTool> toolPlan,
        boolean stop,
        String plannerMode,
        String fallbackReason
) {
    public static PlanEnvelope ruleFallback(String agent, List<PlannedTool> tools, String reason) {
        return new PlanEnvelope(agent, 1.0, null, Map.of(), tools, true, "RULE", reason);
    }

    public Map<String, Object> asEvent() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent", agent);
        m.put("confidence", confidence);
        m.put("userGoal", userGoal);
        m.put("slots", slots == null ? Map.of() : slots);
        m.put("toolPlan", toolPlan == null ? List.of() : toolPlan.stream().map(PlannedTool::asMap).toList());
        m.put("stop", stop);
        m.put("plannerMode", plannerMode);
        if (fallbackReason != null) m.put("fallbackReason", fallbackReason);
        return m;
    }

    public record PlannedTool(String name, Map<String, Object> args, String reason) {
        public PlannedTool(String name, Map<String, Object> args) {
            this(name, args, null);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("args", args == null ? Map.of() : args);
            if (reason != null) m.put("reason", reason);
            return m;
        }
    }
}
