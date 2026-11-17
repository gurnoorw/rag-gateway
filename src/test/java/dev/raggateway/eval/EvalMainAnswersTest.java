package dev.raggateway.eval;

import dev.raggateway.eval.judge.AnswerEvalRunner;
import dev.raggateway.eval.judge.JudgeSettings;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code EvalMain} hands {@code answers ...} to {@link AnswerEvalRunner} (the end-to-end run is AnswersEndToEndIT). */
class EvalMainAnswersTest {

    @Test
    void answersCommandsReachTheAnswerRunnerAndNeverBuildAJudgeWithoutConfirmSpend() {
        AtomicInteger clients = new AtomicInteger();
        AnswerEvalRunner.ClientFactory factory = (s, url, env) -> {
            clients.incrementAndGet();
            throw new AssertionError("no judge client may be built here");
        };
        Map<String, String> env = Map.of(JudgeSettings.API_KEY_ENV, "test-judge-key-not-real");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        assertThat(EvalMain.answers(new String[] {"answers"}, env, out, factory)).isEqualTo(2);
        assertThat(EvalMain.answers(new String[] {"answers", "score", "--answers", "missing.jsonl"}, env, out, factory))
                .isEqualTo(2);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("collect' or 'score'").contains("--confirm-spend");
        assertThat(clients.get()).isZero();
    }
}
