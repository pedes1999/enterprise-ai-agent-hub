package com.enterprisehub.core.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A bounded, multi-round tool-calling loop: send the user's message plus
 * every available tool's specification; each round, if the model asks to
 * call one or more tools, execute them and send the results back, then let
 * the model decide again -- clone, then read a file, then edit it, then
 * run tests is a real shape this needs to support (agent-runtime's
 * sandboxed tools now share one persistent workspace across a whole
 * execution, via SandboxSession -- see its javadoc -- specifically so a
 * multi-round sequence like that can actually see its own earlier steps).
 * Capped at maxToolRounds so a model that never settles on a final
 * answer can't loop (and rack up API cost) forever; if the cap is hit, one
 * last call is made with no tool specifications at all, forcing a text
 * answer instead of yet another tool request.
 */
public class ToolCallingChatEngine {

    /**
     * How many rounds of "model requests tools -> we run them -> feed
     * results back" this allows before forcing a final text-only answer.
     * A simple single-step tool use (e.g. "what time is it") finishes in
     * round 1. A real ticket-to-PR task (clone, explore, read, write,
     * open_pull_request) alone needs ~5; ticket-resolver's system prompt
     * additionally allows one corrected attempt if open_pull_request
     * reports the test run failed, which costs a read/write/retry cycle
     * on top of that. 14 was already headroom over that shape, but
     * test-fixer's prompt asks for a full-suite re-run after EACH
     * individual fix (read failing test, read source, fix, re-run --
     * repeated per failure, potentially several times on a repo with
     * multiple genuine failures), which raised it to 30. 30 still wasn't
     * enough live-run against this repo itself (a multi-module Maven
     * reactor plus an npm frontend) -- both Haiku and Sonnet exhausted all
     * 30 rounds on stack discovery and full-suite runs without ever
     * reaching write_file, let alone open_pull_request, on a
     * single-assertion fix. Raised to 100 to give a genuinely large/slow
     * monorepo enough headroom to actually finish, alongside tightening
     * test-fixer's own prompt (see its system_prompt migration) to scope
     * re-verification to the affected project instead of the whole
     * reactor+frontend every time -- a model that still never converges
     * gets cut off and reported honestly either way (see the incomplete/
     * incompleteReason handling below), this just raises how much genuine
     * work fits before that happens. This is now a configurable per-call
     * default (see maxToolRounds' javadoc), not a hardcoded ceiling --
     * DEFAULT_MAX_TOOL_ROUNDS is what every caller gets unless it
     * explicitly overrides it (app.llm.max-tool-rounds in gateway-api).
     */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 100;

    /**
     * A single tool result (a file's contents, a shell command's stdout/
     * stderr) is capped much tighter here than at the source -- each
     * sandboxed tool already caps its OWN output around 64KB (see e.g.
     * RunShellCommandTool.MAX_OUTPUT_BYTES), but that cap only bounds one
     * call's size once. Every round after that, the SAME already-capped
     * blob gets resent in full as part of the growing conversation history
     * -- a single 64KB read_file result alone costs ~50x this cap on every
     * subsequent round of a long-running execution. This is a second,
     * centralized, provider-agnostic cap applied to what actually enters
     * the conversation (not what the tool itself returns), so it protects
     * every tool uniformly, including ones that don't cap themselves.
     */
    static final int MAX_TOOL_RESULT_CHARS = 8_000;

    /**
     * MAX_TOOL_RESULT_CHARS caps a single result ONCE, at insertion --
     * but that capped result then gets resent in full on every SUBSEQUENT
     * round for the rest of the run, all the way to round 100 if
     * maxToolRounds gets there (nothing in this class ever shrinks or
     * drops an older message otherwise). This is the actual fix for that:
     * once a round's messages are more than this many rounds old, their
     * ToolExecutionResultMessage content (never the AiMessage, and never
     * anything from the last COMPACTION_WINDOW_ROUNDS rounds) is replaced
     * with a short placeholder -- see compactToolResult(). 20 keeps a
     * generous amount of recent context intact (more than any observed
     * ticket-resolver run needs end to end) while still compacting the
     * bulk of a long test-fixer-shaped run. Deliberately a straight
     * sliding window with no special-casing by tool name: if a task
     * genuinely needs an old file's content again, a fresh read_file call
     * is cheap -- keeping every historical read alive forever is not.
     */
    private static final int DEFAULT_COMPACTION_WINDOW_ROUNDS = 20;

    private final ChatModel chatModel;
    private final Map<String, AgentTool> toolsByName;
    private final List<ToolSpecification> toolSpecifications;
    private final ToolExecutionContext executionContext;
    private final String systemPrompt;
    private final Integer maxTokensBudget;
    private final int maxToolRounds;
    private final boolean cacheConversationHistory;
    private final int compactionWindowRounds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolCallingChatEngine(ChatModel chatModel, List<AgentTool> tools, ToolExecutionContext executionContext) {
        this(chatModel, tools, executionContext, null);
    }

    /**
     * systemPrompt is an AgentDefinition's persona/instructions (see
     * gateway-api) -- nullable/blank for callers that don't have one (or
     * still use the 3-arg constructor above), in which case the model just
     * sees the user message with no system message at all, same as before
     * this existed.
     */
    public ToolCallingChatEngine(ChatModel chatModel, List<AgentTool> tools, ToolExecutionContext executionContext, String systemPrompt) {
        this(chatModel, tools, executionContext, systemPrompt, null);
    }

    /**
     * maxTokensBudget is a second, cost-priced stop condition alongside
     * maxToolRounds -- see budgetExceeded()'s javadoc for why both exist
     * rather than one replacing the other. Null means "no budget, rely on
     * maxToolRounds alone" -- e.g. every pre-budget caller/test, and any
     * caller whose tenant/server config genuinely has no limit configured.
     */
    public ToolCallingChatEngine(ChatModel chatModel, List<AgentTool> tools, ToolExecutionContext executionContext,
                                  String systemPrompt, Integer maxTokensBudget) {
        this(chatModel, tools, executionContext, systemPrompt, maxTokensBudget, null);
    }

    /**
     * maxToolRounds: null means "use DEFAULT_MAX_TOOL_ROUNDS" -- every
     * pre-existing caller/test keeps today's behavior unchanged. A
     * defense-in-depth ceiling independent of maxTokensBudget/terminal-tool
     * detection: even a provider that never reports usage and a tool that's
     * never marked terminal still can't loop past this many rounds. Kept
     * configurable (app.llm.max-tool-rounds in gateway-api) rather than a
     * single hardcoded constant specifically because the "right" value is
     * workload-dependent -- see DEFAULT_MAX_TOOL_ROUNDS' own javadoc for how
     * much that number has already had to move (6 -> 14 -> 30 -> 100) as
     * real agents needed more headroom; a deployment that wants a tighter
     * ceiling (or a genuinely bigger one) no longer needs a code change.
     */
    public ToolCallingChatEngine(ChatModel chatModel, List<AgentTool> tools, ToolExecutionContext executionContext,
                                  String systemPrompt, Integer maxTokensBudget, Integer maxToolRounds) {
        this(chatModel, tools, executionContext, systemPrompt, maxTokensBudget, maxToolRounds, false);
    }

    /**
     * cacheConversationHistory: Anthropic-only (see AnthropicMapper.CACHE_CONTROL
     * in langchain4j-anthropic -- agent-core deliberately doesn't depend on
     * that module, see LlmEngineFactory's javadoc, so the "cache_control"/
     * "ephemeral" marker below is a hand-matched string, not a shared
     * constant). false for every other provider/caller -- marking a message
     * this way is a silent no-op for OpenAI/Gemini/Local since their
     * langchain4j mappers don't look for this attribute at all, but there's
     * no benefit to paying the (tiny) extra allocation cost of rebuilding a
     * message on providers that will never read it.
     *
     * When true, the LAST message in the conversation is marked as a cache
     * breakpoint before every generate() call, moving forward each round:
     * round N's call caches everything through round N's newest message, so
     * round N+1 only pays full price for what's actually new since then,
     * instead of resending the entire growing history at full price every
     * single round the same way the system prompt/tools already avoid via
     * cacheSystemMessages/cacheTools.
     */
    public ToolCallingChatEngine(ChatModel chatModel, List<AgentTool> tools, ToolExecutionContext executionContext,
                                  String systemPrompt, Integer maxTokensBudget, Integer maxToolRounds,
                                  boolean cacheConversationHistory) {
        this(chatModel, tools, executionContext, systemPrompt, maxTokensBudget, maxToolRounds,
                cacheConversationHistory, null);
    }

    /**
     * compactionWindowRounds: null means "use DEFAULT_COMPACTION_WINDOW_ROUNDS" --
     * every pre-existing caller/test keeps today's (now-compacting)
     * behavior. See DEFAULT_COMPACTION_WINDOW_ROUNDS' own javadoc for what
     * this controls and why 20 is the default. Kept configurable the same
     * way maxToolRounds already is, for the same reason: the right value
     * is workload-dependent, and a deployment that wants a smaller/larger
     * window (or -- by passing Integer.MAX_VALUE -- effectively none)
     * shouldn't need a code change to get it.
     */
    public ToolCallingChatEngine(ChatModel chatModel, List<AgentTool> tools, ToolExecutionContext executionContext,
                                  String systemPrompt, Integer maxTokensBudget, Integer maxToolRounds,
                                  boolean cacheConversationHistory, Integer compactionWindowRounds) {
        this.chatModel = chatModel;
        this.toolsByName = new LinkedHashMap<>();
        tools.forEach(tool -> toolsByName.put(tool.name(), tool));
        this.toolSpecifications = tools.stream().map(ToolCallingChatEngine::toSpecification).toList();
        this.executionContext = executionContext;
        this.systemPrompt = systemPrompt;
        this.maxTokensBudget = maxTokensBudget;
        this.maxToolRounds = maxToolRounds != null ? maxToolRounds : DEFAULT_MAX_TOOL_ROUNDS;
        this.cacheConversationHistory = cacheConversationHistory;
        this.compactionWindowRounds = compactionWindowRounds != null ? compactionWindowRounds : DEFAULT_COMPACTION_WINDOW_ROUNDS;
    }

    /** Returns the final text answer, and whether a tool was actually invoked along the way (in any round). */
    public ToolChatResult chat(String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(userMessage));
        boolean toolWasUsed = false;
        // Null until the first response that actually carries usage data --
        // some providers/mocks return none at all, and that's "unknown", not
        // "zero tokens spent" (see ToolChatResult's fields).
        TokenUsage totalUsage = null;
        // -1 means "no message-level cache breakpoint placed yet". Anthropic
        // allows at most 4 cache_control breakpoints per request (system +
        // tools already use one each via cacheSystemMessages/cacheTools) --
        // this index is where the CURRENT one lives, moved forward each
        // round rather than left in place, so a long-running execution never
        // accumulates more than a single extra breakpoint here regardless of
        // how many rounds it takes.
        int cacheBreakpointIndex = -1;
        // One [startIndex, endIndex) entry per round that added messages
        // (a round ending in a plain text answer never reaches the
        // "toolWasUsed = true" block below, so never gets an entry) --
        // see compactOldRoundIfNeeded() for how this drives compaction.
        List<int[]> roundMessageRanges = new ArrayList<>();

        for (int round = 0; round < maxToolRounds; round++) {
            if (budgetExceeded(totalUsage)) {
                // Exceeded during a PREVIOUS round -- stop before spending
                // more on another generate() call, same "force one final
                // text-only answer" handling as the round-cap path below.
                break;
            }
            cacheBreakpointIndex = moveCacheBreakpointIfEnabled(messages, cacheBreakpointIndex);
            ChatResponse response = generate(messages, toolSpecifications, totalUsage);
            totalUsage = accumulate(totalUsage, response.tokenUsage());
            AiMessage aiMessage = response.aiMessage();
            if (!aiMessage.hasToolExecutionRequests()) {
                List<ToolExecutionRequest> recoveredToolCalls = recoverToolCallsFromText(aiMessage.text());
                if (!recoveredToolCalls.isEmpty()) {
                    aiMessage = AiMessage.builder().toolExecutionRequests(recoveredToolCalls).build();
                }
            }

            if (!aiMessage.hasToolExecutionRequests()) {
                // A "final" answer that was actually cut off mid-generation (the
                // model ran out of output tokens before it could finish, possibly
                // before it even got to request the next tool call) is not a real
                // stopping point -- treating it as one silently ends the task
                // early with no error anywhere. finishReason() carries exactly
                // that signal (LENGTH), already returned by every provider call,
                // previously discarded here.
                boolean truncated = response.finishReason() == FinishReason.LENGTH;
                String incompleteReason = truncated
                        ? "Model response was truncated (hit the max_tokens limit) before it finished."
                        : null;
                return new ToolChatResult(aiMessage.text(), toolWasUsed, truncated, incompleteReason,
                        inputTokens(totalUsage), outputTokens(totalUsage), totalTokens(totalUsage));
            }

            toolWasUsed = true;
            int roundStartIndex = messages.size();
            messages.add(aiMessage);
            boolean terminalSuccess = false;
            List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
            List<String> results = executeToolsInOrder(requests);
            for (int i = 0; i < requests.size(); i++) {
                ToolExecutionRequest request = requests.get(i);
                String result = results.get(i);
                messages.add(ToolExecutionResultMessage.from(request, result));
                if (isTerminalSuccess(request.name(), result)) {
                    terminalSuccess = true;
                }
            }
            roundMessageRanges.add(new int[] {roundStartIndex, messages.size()});
            compactOldRoundIfNeeded(messages, roundMessageRanges);

            if (terminalSuccess) {
                // A tool like open_pull_request just reported that the actual
                // goal was achieved -- don't offer the model another round of
                // tools and trust it to notice and stop on its own (it often
                // won't, see AgentTool.isTerminalSuccess()'s javadoc); force
                // one final text-only answer instead, same mechanism as the
                // round-cap path below but a genuine stopping point, so this
                // is NOT incomplete.
                cacheBreakpointIndex = moveCacheBreakpointIfEnabled(messages, cacheBreakpointIndex);
                ChatResponse finalResponse = generate(messages, List.of(), totalUsage);
                totalUsage = accumulate(totalUsage, finalResponse.tokenUsage());
                return new ToolChatResult(finalResponse.aiMessage().text(), toolWasUsed, false, null,
                        inputTokens(totalUsage), outputTokens(totalUsage), totalTokens(totalUsage));
            }
        }

        // Either the round cap was exhausted or the loop above broke early on
        // an exceeded budget -- both force a text answer instead of letting
        // the model request yet another round it won't get to use, and both
        // are "not a real stopping point" the same way a truncated response
        // is. budgetExceeded() here re-checks the SAME totalUsage the break
        // (if any) just fired on, so it reliably tells the two apart.
        moveCacheBreakpointIfEnabled(messages, cacheBreakpointIndex);
        ChatResponse finalResponse = generate(messages, List.of(), totalUsage);
        totalUsage = accumulate(totalUsage, finalResponse.tokenUsage());
        String incompleteReason = budgetExceeded(totalUsage)
                ? "Agent execution stopped after exceeding its token budget (" + maxTokensBudget + " tokens)."
                : "Agent used all " + maxToolRounds + " allowed tool-call rounds without finishing.";
        return new ToolChatResult(finalResponse.aiMessage().text(), toolWasUsed, true, incompleteReason,
                inputTokens(totalUsage), outputTokens(totalUsage), totalTokens(totalUsage));
    }

    /**
     * ChatModel's new (1.x) API takes tool specifications via
     * ChatRequestParameters, not a positional arg -- this restores the old
     * generate(messages, tools) call shape everywhere below it's used.
     *
     * usageSoFar is whatever totalUsage had accumulated from every ROUND
     * BEFORE this call -- not this call's own result, which doesn't exist
     * yet. A provider call can fail after several earlier rounds already
     * succeeded and were genuinely billed (a rate limit, a network blip, or
     * literally running out of API credit mid-run -- all live-observed), and
     * without this, that real spend was previously invisible: the exception
     * propagated with no token data at all, even though most of the run's
     * cost had already happened. See PartialUsageException's javadoc for
     * how a caller recovers it.
     */
    private ChatResponse generate(List<ChatMessage> messages, List<ToolSpecification> tools, TokenUsage usageSoFar) {
        try {
            return chatModel.chat(ChatRequest.builder().messages(messages).toolSpecifications(tools).build());
        } catch (RuntimeException e) {
            throw new PartialUsageException(e.getMessage(), e,
                    inputTokens(usageSoFar), outputTokens(usageSoFar), totalTokens(usageSoFar));
        }
    }

    /**
     * Thrown instead of letting a provider-call failure (rate limit,
     * insufficient credit, network error, malformed response) propagate
     * bare -- carries whatever input/output/total tokens had genuinely
     * accumulated from earlier rounds of THIS execution before the failing
     * call, the same three fields ToolChatResult reports on every other
     * exit path. A caller that wants that partial spend recorded (see
     * AgentJobWorker) catches this specifically, ahead of a plainer
     * RuntimeException catch for anything else; a caller that doesn't care
     * (e.g. AgentPingService, which never persists token usage for its
     * synchronous spike endpoint) can keep treating it as an ordinary
     * RuntimeException -- message and cause are preserved either way.
     */
    public static final class PartialUsageException extends RuntimeException {
        private final Integer inputTokens;
        private final Integer outputTokens;
        private final Integer totalTokens;

        public PartialUsageException(String message, Throwable cause, Integer inputTokens, Integer outputTokens, Integer totalTokens) {
            super(message, cause);
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalTokens = totalTokens;
        }

        public Integer inputTokens() {
            return inputTokens;
        }

        public Integer outputTokens() {
            return outputTokens;
        }

        public Integer totalTokens() {
            return totalTokens;
        }
    }

    private static final String ANTHROPIC_CACHE_CONTROL_KEY = "cache_control";
    private static final String ANTHROPIC_CACHE_CONTROL_VALUE = "ephemeral";

    /**
     * Anthropic reads a "cache_control": "ephemeral" attribute on a message
     * to mean "cache everything through here" (see AnthropicMapper in
     * langchain4j-anthropic, which checks UserMessage/AiMessage/
     * ToolExecutionResultMessage.attributes() for exactly this key/value --
     * not part of the public langchain4j API, hand-matched here rather than
     * adding a dependency on that module, see the constructor's javadoc).
     * Un-marks whatever message previously held the breakpoint (if any)
     * before marking the new last message, so exactly one of these exists
     * in the request at a time -- see cacheBreakpointIndex's javadoc in
     * chat() for why that matters. Returns the new breakpoint's index (or
     * the unchanged previousIndex when disabled/empty).
     */
    private int moveCacheBreakpointIfEnabled(List<ChatMessage> messages, int previousIndex) {
        if (!cacheConversationHistory || messages.isEmpty()) {
            return previousIndex;
        }
        if (previousIndex >= 0 && previousIndex < messages.size()) {
            messages.set(previousIndex, withCacheControlAttribute(messages.get(previousIndex), false));
        }
        int lastIndex = messages.size() - 1;
        messages.set(lastIndex, withCacheControlAttribute(messages.get(lastIndex), true));
        return lastIndex;
    }

    /** Adds or removes ONLY the cache-control key, preserving every other attribute a message might carry (e.g. thinking signatures) rather than wiping its whole attributes map. */
    private static ChatMessage withCacheControlAttribute(ChatMessage message, boolean cacheable) {
        if (message instanceof UserMessage userMessage) {
            return userMessage.toBuilder().attributes(withCacheControlKey(userMessage.attributes(), cacheable)).build();
        }
        if (message instanceof AiMessage aiMessage) {
            return aiMessage.toBuilder().attributes(withCacheControlKey(aiMessage.attributes(), cacheable)).build();
        }
        if (message instanceof ToolExecutionResultMessage toolResultMessage) {
            return toolResultMessage.toBuilder().attributes(withCacheControlKey(toolResultMessage.attributes(), cacheable)).build();
        }
        // SystemMessage is never in this list (see chat()'s javadoc note on
        // how it's split out) -- any other/future message type is returned
        // unchanged rather than failing the whole call over a cache hint.
        return message;
    }

    private static Map<String, Object> withCacheControlKey(Map<String, Object> existing, boolean cacheable) {
        Map<String, Object> updated = new LinkedHashMap<>(existing);
        if (cacheable) {
            updated.put(ANTHROPIC_CACHE_CONTROL_KEY, ANTHROPIC_CACHE_CONTROL_VALUE);
        } else {
            updated.remove(ANTHROPIC_CACHE_CONTROL_KEY);
        }
        return updated;
    }

    /** null-safe TokenUsage.add() -- a provider/mock response with no usage data leaves the running total unchanged. */
    private static TokenUsage accumulate(TokenUsage total, TokenUsage next) {
        if (next == null) {
            return total;
        }
        return total == null ? next : total.add(next);
    }

    /**
     * True once totalUsage's running total has reached maxTokensBudget.
     * Deliberately a SEPARATE stop condition from maxToolRounds, not a
     * replacement for it: this can only fire once a provider response has
     * actually reported usage, so a provider/mock that never does (totalUsage
     * stays null forever) would let a run loop unbounded on budget alone --
     * maxToolRounds is the backstop that still catches that case. A null
     * maxTokensBudget (no budget configured) always returns false, same
     * effect as today's round-cap-only behavior.
     */
    private boolean budgetExceeded(TokenUsage totalUsage) {
        return maxTokensBudget != null && totalUsage != null && totalUsage.totalTokenCount() != null
                && totalUsage.totalTokenCount() >= maxTokensBudget;
    }

    private static Integer inputTokens(TokenUsage usage) {
        return usage == null ? null : usage.inputTokenCount();
    }

    private static Integer outputTokens(TokenUsage usage) {
        return usage == null ? null : usage.outputTokenCount();
    }

    private static Integer totalTokens(TokenUsage usage) {
        return usage == null ? null : usage.totalTokenCount();
    }

    private boolean isTerminalSuccess(String toolName, String result) {
        AgentTool tool = toolsByName.get(toolName);
        return tool != null && tool.isTerminalSuccess(result);
    }

    /**
     * A single AiMessage round can carry several independent tool calls
     * (e.g. reading two unrelated files) -- the model already decided they
     * don't depend on each other's output, or it wouldn't have requested
     * them together. Running them concurrently instead of one at a time
     * cuts wall-clock latency for that round to the slowest call instead of
     * their sum. A single-request round (the common case) skips the
     * executor entirely -- no thread-pool overhead for the typical case.
     * Virtual threads suit this well since every real tool call here is
     * I/O-bound (an HTTP call to the sandbox sidecar, or the LLM's own
     * provider call for a sub-delegation-style tool). Results are returned
     * in the SAME order as requests, regardless of completion order --
     * message history and which result flips terminalSuccess must stay
     * deterministic. executeTool() already isolates exceptions per call
     * (returns an error string rather than throwing), so a failure in one
     * concurrent call can't take down the others.
     */
    private List<String> executeToolsInOrder(List<ToolExecutionRequest> requests) {
        if (requests.size() <= 1) {
            return requests.stream().map(this::executeTool).toList();
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = requests.stream()
                    .map(request -> executor.submit(() -> executeTool(request)))
                    .toList();
            List<String> results = new ArrayList<>(futures.size());
            for (Future<String> future : futures) {
                results.add(awaitResult(future));
            }
            return results;
        }
    }

    private static String awaitResult(Future<String> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error executing tool: interrupted while waiting for result";
        } catch (java.util.concurrent.ExecutionException e) {
            // executeTool() already catches every Exception internally and
            // returns an error string instead of throwing -- this branch is
            // effectively unreachable in practice, but kept as a safe
            // fallback rather than letting Future.get()'s checked exception
            // propagate uncaught.
            return "Error executing tool: " + e.getCause().getMessage();
        }
    }

    /**
     * roundMessageRanges has exactly one entry per round that has finished
     * so far, in order -- so once it holds more than compactionWindowRounds
     * entries, the entry at (size - 1 - compactionWindowRounds) is exactly
     * the one round that just aged out of the window for the first time
     * (each index reaches that position exactly once as rounds progress,
     * so this never re-compacts an already-compacted round). Everything
     * from the most recent compactionWindowRounds rounds is left
     * untouched.
     */
    private void compactOldRoundIfNeeded(List<ChatMessage> messages, List<int[]> roundMessageRanges) {
        if (roundMessageRanges.size() <= compactionWindowRounds) {
            return;
        }
        int[] range = roundMessageRanges.get(roundMessageRanges.size() - 1 - compactionWindowRounds);
        for (int i = range[0]; i < range[1]; i++) {
            if (messages.get(i) instanceof ToolExecutionResultMessage resultMessage) {
                messages.set(i, compactToolResult(resultMessage));
            }
        }
    }

    /**
     * Replaces the result's TEXT only, in place -- the message itself
     * (same position, same tool_use_id pairing with its AiMessage) stays
     * exactly where it was, so this can never desync the tool_use/
     * tool_result pairing Anthropic's API (and langchain4j's own message
     * ordering) requires. AiMessages are never touched by compaction at
     * all -- only the (already-capped, see MAX_TOOL_RESULT_CHARS) result
     * text a past round's tool call produced.
     */
    private static ToolExecutionResultMessage compactToolResult(ToolExecutionResultMessage message) {
        String text = message.text();
        if (text == null || text.isEmpty()) {
            return message;
        }
        String placeholder = "[tool result from an earlier round, " + text.length()
                + " chars, omitted to save context -- call the tool again if you need this]";
        // ToolExecutionResultMessage.Builder rejects text+contents both being
        // set (langchain4j's message content is either plain text OR a
        // multi-part contents list, never both) -- toBuilder() carries the
        // original's contents forward, so it must be explicitly cleared
        // here or build() throws even though this only means to replace text.
        return message.toBuilder().text(placeholder).contents(List.<dev.langchain4j.data.message.Content>of()).build();
    }

    private String executeTool(ToolExecutionRequest request) {
        AgentTool tool = toolsByName.get(request.name());
        if (tool == null) {
            return "Error: no tool registered with name '" + request.name() + "'";
        }
        try {
            return truncateForHistory(tool.execute(executionContext, parseArguments(request.arguments())));
        } catch (Exception e) {
            return "Error executing tool '" + request.name() + "': " + e.getMessage();
        }
    }

    /** See MAX_TOOL_RESULT_CHARS' javadoc -- a second cap on top of whatever each tool already enforces on itself. */
    private static String truncateForHistory(String result) {
        if (result == null || result.length() <= MAX_TOOL_RESULT_CHARS) {
            return result;
        }
        return result.substring(0, MAX_TOOL_RESULT_CHARS)
                + "\n... (truncated " + (result.length() - MAX_TOOL_RESULT_CHARS) + " more characters -- "
                + "re-run this tool with a narrower target if you need what was cut off)";
    }

    /**
     * Some models -- observed with qwen2.5-coder:7b served locally through
     * Ollama's OpenAI-compatible endpoint (see LlmEngineFactory's LOCAL
     * branch), not exclusive to it -- don't reliably populate the OpenAI
     * wire format's structured tool_calls field even though the request
     * genuinely declared tools. Instead the call comes back as ordinary
     * assistant TEXT shaped like {"name": "...", "arguments": {...}}, which
     * langchain4j maps to an AiMessage with hasToolExecutionRequests() ==
     * false -- without this, that JSON silently becomes the execution's
     * "final answer" and the tool never actually runs (the exact failure
     * this recovers from). Deliberately conservative: only fires when the
     * ENTIRE trimmed response is a single JSON object (or array of them)
     * shaped exactly like a tool call, and only when every "name" matches
     * an actually-registered tool -- a real prose final answer that merely
     * mentions JSON never matches this, and one malformed/unrecognized
     * entry in an array rejects the whole batch rather than guessing.
     */
    private List<ToolExecutionRequest> recoverToolCallsFromText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(trimmed);
        } catch (Exception e) {
            return List.of();
        }
        List<JsonNode> candidates = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(candidates::add);
        } else {
            candidates.add(root);
        }

        List<ToolExecutionRequest> recovered = new ArrayList<>();
        int index = 0;
        for (JsonNode candidate : candidates) {
            JsonNode nameNode = candidate.get("name");
            JsonNode argumentsNode = candidate.get("arguments");
            if (nameNode == null || !nameNode.isTextual() || argumentsNode == null
                    || !toolsByName.containsKey(nameNode.asText())) {
                return List.of();
            }
            // The model wrote arguments as a real JSON object (the observed shape)
            // rather than the OpenAI-protocol-correct pre-serialized string --
            // re-serialize so parseArguments() below sees exactly what it always
            // expects, regardless of which shape actually arrived.
            String argumentsJson = argumentsNode.isTextual() ? argumentsNode.asText() : argumentsNode.toString();
            recovered.add(ToolExecutionRequest.builder()
                    .id("recovered-" + index++)
                    .name(nameNode.asText())
                    .arguments(argumentsJson)
                    .build());
        }
        return recovered;
    }

    private Map<String, String> parseArguments(String argumentsJson) throws Exception {
        Map<String, Object> raw = objectMapper.readValue(argumentsJson, new TypeReference<>() {
        });
        Map<String, String> arguments = new LinkedHashMap<>();
        // A model declining an optional parameter (see AgentTool.optionalParameterNames())
        // may send an explicit JSON null for it instead of omitting the key
        // entirely -- treat both the same way (key absent from the result),
        // rather than String.valueOf(null) silently turning it into the
        // four-character string "null" that a tool would then treat as a
        // real (garbage) argument value.
        raw.forEach((key, value) -> {
            if (value != null) {
                arguments.put(key, String.valueOf(value));
            }
        });
        return arguments;
    }

    private static ToolSpecification toSpecification(AgentTool tool) {
        // Every AgentTool parameter is a string (see its own javadoc) -- addStringProperty
        // for all of them, then required() lists only the non-optional ones. Replaces the
        // old per-parameter addParameter/addOptionalParameter(JsonSchemaProperty...) API,
        // which the 1.x ToolSpecification.Builder no longer has.
        Set<String> optionalParams = tool.optionalParameterNames();
        JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
        List<String> requiredParams = new ArrayList<>();
        tool.parameterDescriptions().forEach((paramName, paramDescription) -> {
            schema.addStringProperty(paramName, paramDescription);
            if (!optionalParams.contains(paramName)) {
                requiredParams.add(paramName);
            }
        });
        schema.required(requiredParams);

        return ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(schema.build())
                .build();
    }

    /**
     * incomplete is true when reply is NOT a genuine final answer -- either
     * the model's response was truncated by max_tokens before it finished,
     * or the round cap was hit while the model still wanted to keep calling
     * tools. In both cases the model never reached a real stopping point, so
     * a caller (AgentJobWorker) should treat this as a failed execution with
     * incompleteReason as the error, not a successful one with a
     * suspiciously short (or blank) reply and no explanation anywhere.
     *
     * inputTokens/outputTokens/totalTokens are summed across every
     * chatModel.generate() call made during chat() (every round, plus
     * whichever "final" call ended it) -- null, not zero, when not a single
     * one of those responses carried usage data, so a caller can tell
     * "genuinely free" apart from "we don't know".
     */
    public record ToolChatResult(String reply, boolean toolWasUsed, boolean incomplete, String incompleteReason,
                                  Integer inputTokens, Integer outputTokens, Integer totalTokens) {

        /** For callers that don't need token usage -- e.g. existing tests predating this field. */
        public ToolChatResult(String reply, boolean toolWasUsed, boolean incomplete, String incompleteReason) {
            this(reply, toolWasUsed, incomplete, incompleteReason, null, null, null);
        }
    }
}
