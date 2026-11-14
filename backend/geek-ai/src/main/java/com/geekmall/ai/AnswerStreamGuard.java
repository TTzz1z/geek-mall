package com.geekmall.ai;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Validates complete natural-language segments before they cross the SSE boundary. */
final class AnswerStreamGuard implements Consumer<String> {
    private static final int MAX_SEGMENT_CHARS = 2048;
    private static final Pattern MONEY_MOVEMENT = Pattern.compile(
            "(?:已\\s*(?:经\\s*)?(?:(?:为你|帮你)\\s*)?(?:成功\\s*)?(?:支付|付款|退款|扣款|提交订单)|"
                    + "(?:支付|付款|退款|扣款|订单提交)\\s*(?:已\\s*)?成功|"
                    + "(?:payment|refund)\\s+(?:completed|succeeded))", Pattern.CASE_INSENSITIVE);

    private final String draft;
    private final List<Map<String, Object>> toolResults;
    private final Consumer<String> output;
    private final PromptEchoGuard promptEcho;
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder emitted = new StringBuilder();

    AnswerStreamGuard(String systemPrompt, String draft, List<Map<String, Object>> toolResults,
                      Consumer<String> output) {
        this.draft = draft;
        this.toolResults = toolResults;
        this.output = output;
        this.promptEcho = new PromptEchoGuard(systemPrompt, "工具结果摘要草稿", "用户原话");
    }

    @Override
    public void accept(String delta) {
        for (int i = 0; i < delta.length(); i++) {
            char ch = delta.charAt(i);
            pending.append(ch);
            if (pending.length() > MAX_SEGMENT_CHARS) throw new IllegalArgumentException("answer segment too long");
            boolean sentenceEnd = "。！？!?\n".indexOf(ch) >= 0;
            boolean englishEnd = ch == '.' && pending.length() > 1
                    && !Character.isDigit(pending.charAt(pending.length() - 2));
            if (sentenceEnd || englishEnd) emitValidated();
        }
    }

    void finish() {
        if (!pending.isEmpty()) emitValidated();
    }

    String text() {
        return emitted.toString();
    }

    private void emitValidated() {
        String segment = pending.toString();
        // Keep context across sentence boundaries as well as provider delta boundaries.
        String context = emitted.substring(Math.max(0, emitted.length() - 256)) + segment;
        if (promptEcho.containsEcho(context)) throw new IllegalArgumentException("internal prompt echo");
        if (MONEY_MOVEMENT.matcher(context).find()) throw new IllegalArgumentException("unsupported money movement claim");
        if (context.isBlank()) return;
        var grounded = AnswerGrounding.groundSegment(context, draft, toolResults);
        if (grounded.usedDraft() || !grounded.issues().isEmpty()) throw new IllegalArgumentException("ungrounded answer segment");
        output.accept(segment);
        emitted.append(segment);
        pending.setLength(0);
    }

}
