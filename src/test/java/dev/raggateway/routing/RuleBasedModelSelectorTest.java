package dev.raggateway.routing;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ModelProperties;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Message;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.routing.RoutingProperties.Rule;
import dev.raggateway.routing.RoutingProperties.When;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** t06 selection rules (D-42): auto, explicit, tenant allowed models, startup checks, the decisions counter. */
class RuleBasedModelSelectorTest {

    static final String OPUS = "claude-opus-5-5";
    static final String SONNET = "claude-sonnet-5-5";
    static final String HAIKU = "claude-haiku-5-5";
    static final String LLAMA = "llama3.2:1b";

    static final GatewayProperties CATALOG = new GatewayProperties(1024, 8192, Map.of(
            OPUS, model(), SONNET, model(), HAIKU, model(), LLAMA, model()), Map.of());

    static final Tenant ANY = tenant();

    final MeterRegistry registry = new SimpleMeterRegistry();
    final RuleBasedModelSelector starting = new RuleBasedModelSelector(CATALOG, RoutingProperties.defaults(), registry);

    static ModelProperties model() {
        return new ModelProperties("claude", "x", BigDecimal.ONE, BigDecimal.ONE);
    }

    static Tenant tenant(String... allowed) {
        return new Tenant("t1", "Tenant 1", 10_000, BigDecimal.TEN, Set.of(allowed));
    }

    static ChatRequest request(String model, int maxTokens, String schema) {
        return new ChatRequest(model, List.of(new Message(Role.USER, "q")), maxTokens, null, null, List.of(),
                false, false, schema);
    }

    static ChatRequest auto(int maxTokens) {
        return request(ChatRequest.AUTO, maxTokens, null);
    }

    double decisions(String rule, String model) {
        return registry.find(MetricNames.ROUTING_DECISIONS).tag("rule", rule).tag("model", model).counter().count();
    }

    static RuleBasedModelSelector selector(String defaultModel, Rule... rules) {
        return new RuleBasedModelSelector(CATALOG, new RoutingProperties(defaultModel, Arrays.asList(rules)),
                new SimpleMeterRegistry());
    }

    // ---- auto with the starting rules ----

    @Test
    void ac_t06_1_shortPromptWithSmallMaxTokensGoesToTheCheapTier() {
        assertThat(starting.select(auto(64), ANY, 100)).isEqualTo(new ModelSelection(HAIKU, "short"));
        assertThat(decisions("short", HAIKU)).isEqualTo(1.0);
    }

    @Test
    void theShortRuleBoundsAreInclusive() {
        assertThat(starting.select(auto(1_024), ANY, 2_000).model()).isEqualTo(HAIKU);
    }

    @Test
    void ac_t06_2_promptAboveTheTokenThresholdGoesToTheStrongTier() {
        assertThat(starting.select(auto(64), ANY, 2_001)).isEqualTo(new ModelSelection(OPUS, "default"));
        assertThat(decisions("default", OPUS)).isEqualTo(1.0);
    }

    @Test
    void largeMaxTokensOrASchemaGoesToTheStrongTier() {
        assertThat(starting.select(auto(1_025), ANY, 10).model()).isEqualTo(OPUS);
        assertThat(starting.select(request(ChatRequest.AUTO, 64, "{\"type\":\"object\"}"), ANY, 10).model())
                .isEqualTo(OPUS);
    }

    // ---- explicit model ----

    @Test
    void ac_t06_3_anExplicitCatalogModelIsPassedThroughWithoutRule() {
        assertThat(starting.select(request(SONNET, 4_000, null), ANY, 50_000)).isEqualTo(new ModelSelection(SONNET, null));
        assertThat(decisions("explicit", SONNET)).isEqualTo(1.0);
    }

    @Test
    void anUnknownExplicitModelIs400() {
        assertThatThrownBy(() -> starting.select(request("gpt-4o", 64, null), ANY, 10))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST))
                .hasMessageContaining("unknown model");
    }

    @Test
    void ac_t06_4_anExplicitModelOutsideTheTenantsListIs403() {
        assertThatThrownBy(() -> starting.select(request(OPUS, 64, null), tenant(HAIKU), 10))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MODEL_NOT_ALLOWED));
        assertThat(decisions("explicit", OPUS)).isZero();
    }

    @Test
    void anUnknownModelIs400EvenIfTheTenantListNamesIt() {
        assertThatThrownBy(() -> starting.select(request("gpt-4o", 64, null), tenant("gpt-4o"), 10))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    // ---- auto and the tenant's allowed models ----

    @Test
    void aRuleWhoseModelTheTenantMayNotUseIsSkipped() {
        assertThat(starting.select(auto(64), tenant(OPUS), 10)).isEqualTo(new ModelSelection(OPUS, "default"));
    }

    @Test
    void theNextAllowedMatchingRuleWins() {
        var s = selector(OPUS, new Rule("tiny", HAIKU, new When(100, null, null, null)),
                new Rule("small", SONNET, new When(500, null, null, null)));
        assertThat(s.select(auto(64), tenant(SONNET, OPUS), 50)).isEqualTo(new ModelSelection(SONNET, "small"));
    }

    @Test
    void ac_t06_4_autoWithNoAllowedRuleAndADisallowedDefaultIs403() {
        assertThatThrownBy(() -> starting.select(auto(64), tenant(HAIKU), 5_000))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MODEL_NOT_ALLOWED));
        assertThatThrownBy(() -> starting.select(auto(64), tenant(SONNET), 10))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.MODEL_NOT_ALLOWED));
    }

    // ---- conditions ----

    @Test
    void ac_t06_6_noRuleMatchesGivesTheConfiguredDefault() {
        var s = selector(SONNET, new Rule("never", HAIKU, new When(null, 1_000_000, null, null)));
        assertThat(s.select(auto(64), ANY, 10)).isEqualTo(new ModelSelection(SONNET, "default"));
        assertThat(selector(SONNET).select(auto(64), ANY, 10)).isEqualTo(new ModelSelection(SONNET, "default"));
    }

    @Test
    void everyGivenConditionMustHold() {
        var s = selector(OPUS, new Rule("mid", SONNET, new When(1_000, 100, 512, true)));
        String schema = "{\"type\":\"object\"}";

        assertThat(s.select(request(ChatRequest.AUTO, 512, schema), ANY, 100).model()).isEqualTo(SONNET);
        assertThat(s.select(request(ChatRequest.AUTO, 512, schema), ANY, 99).model()).isEqualTo(OPUS);
        assertThat(s.select(request(ChatRequest.AUTO, 512, schema), ANY, 1_001).model()).isEqualTo(OPUS);
        assertThat(s.select(request(ChatRequest.AUTO, 513, schema), ANY, 100).model()).isEqualTo(OPUS);
        assertThat(s.select(request(ChatRequest.AUTO, 512, null), ANY, 100).model()).isEqualTo(OPUS);
    }

    @Test
    void aRuleWithoutConditionsAlwaysMatches() {
        var s = selector(OPUS, new Rule("everything", LLAMA, null));
        assertThat(s.select(auto(8_000), ANY, 100_000)).isEqualTo(new ModelSelection(LLAMA, "everything"));
    }

    // ---- metrics ----

    @Test
    void everyPossibleDecisionSeriesExistsAtZeroBeforeTraffic() {
        var fresh = new SimpleMeterRegistry();
        new RuleBasedModelSelector(CATALOG, RoutingProperties.defaults(), fresh);

        assertThat(fresh.find(MetricNames.ROUTING_DECISIONS).counters())
                .extracting(c -> c.getId().getTag("rule") + "/" + c.getId().getTag("model"))
                .containsExactlyInAnyOrder("short/" + HAIKU, "default/" + OPUS, "explicit/" + OPUS,
                        "explicit/" + SONNET, "explicit/" + HAIKU, "explicit/" + LLAMA);
        assertThat(fresh.find(MetricNames.ROUTING_DECISIONS).counters()).allSatisfy(c -> assertThat(c.count()).isZero());
    }

    @Test
    void rejectionsAreNotCounted() {
        assertThatThrownBy(() -> starting.select(auto(64), tenant(HAIKU), 5_000)).isInstanceOf(GatewayException.class);
        assertThat(registry.find(MetricNames.ROUTING_DECISIONS).counters()).allSatisfy(c -> assertThat(c.count()).isZero());
    }

    // ---- validation of the rule set ----

    @Test
    void ruleNamesAreBoundedIdentifiersAndNotReserved() {
        assertThatThrownBy(() -> new Rule("Short Prompt", HAIKU, null)).hasMessageContaining("must match");
        assertThatThrownBy(() -> new Rule("x".repeat(41), HAIKU, null)).hasMessageContaining("must match");
        assertThatThrownBy(() -> new Rule("default", HAIKU, null)).hasMessageContaining("reserved");
        assertThatThrownBy(() -> new Rule("explicit", HAIKU, null)).hasMessageContaining("reserved");
        assertThatThrownBy(() -> new Rule("short", " ", null)).hasMessageContaining("no model");
    }

    @Test
    void duplicateNamesAndImpossibleBoundsAreRejected() {
        var a = new Rule("a", HAIKU, null);
        assertThatThrownBy(() -> new RoutingProperties(OPUS, List.of(a, a))).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new When(10, 20, null, null)).hasMessageContaining("could never match");
        assertThatThrownBy(() -> new When(null, null, -1, null)).hasMessageContaining(">= 0");
    }

    @Test
    void aRuleOrDefaultOutsideTheCatalogFailsWithEveryOffenderNamed() {
        var routing = new RoutingProperties("claude-opus-4", List.of(new Rule("ok", HAIKU, null),
                new Rule("typo", "claude-haiku-55", null)));
        assertThatThrownBy(() -> new RuleBasedModelSelector(CATALOG, routing, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rule 'typo' -> 'claude-haiku-55'")
                .hasMessageContaining("default-model -> 'claude-opus-4'")
                .hasMessageNotContaining("rule 'ok'");
    }

    // ---- binding from configuration (the real property names) ----

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({GatewayProperties.class, RoutingProperties.class})
    static class RoutingOnly {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        RuleBasedModelSelector selector(GatewayProperties g, RoutingProperties r, MeterRegistry m) {
            return new RuleBasedModelSelector(g, r, m);
        }
    }

    @Nested
    class FromConfig {

        final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(RoutingOnly.class)
                .withPropertyValues(
                        "gateway.models[claude-opus-5-5].provider=claude",
                        "gateway.models[claude-sonnet-5-5].provider=claude",
                        "gateway.models[claude-haiku-5-5].provider=claude");

        static final String[] HAIKU_FIRST = {
                "gateway.routing.default-model=claude-opus-5-5",
                "gateway.routing.rules[0].name=short",
                "gateway.routing.rules[0].model=claude-haiku-5-5",
                "gateway.routing.rules[0].when.max-input-tokens=2000",
                "gateway.routing.rules[1].name=no_schema",
                "gateway.routing.rules[1].model=claude-sonnet-5-5",
                "gateway.routing.rules[1].when.has-schema=false"};

        static final String[] SONNET_FIRST = {
                "gateway.routing.default-model=claude-opus-5-5",
                "gateway.routing.rules[0].name=no_schema",
                "gateway.routing.rules[0].model=claude-sonnet-5-5",
                "gateway.routing.rules[0].when.has-schema=false",
                "gateway.routing.rules[1].name=short",
                "gateway.routing.rules[1].model=claude-haiku-5-5",
                "gateway.routing.rules[1].when.max-input-tokens=2000"};

        @Test
        void ac_t06_5_changingRuleOrderInConfigChangesTheOutcome() {
            ChatRequest shortNoSchema = auto(64);

            runner.withPropertyValues(HAIKU_FIRST).run(ctx -> assertThat(ctx.getBean(RuleBasedModelSelector.class)
                    .select(shortNoSchema, ANY, 100)).isEqualTo(new ModelSelection(HAIKU, "short")));
            runner.withPropertyValues(SONNET_FIRST).run(ctx -> assertThat(ctx.getBean(RuleBasedModelSelector.class)
                    .select(shortNoSchema, ANY, 100)).isEqualTo(new ModelSelection(SONNET, "no_schema")));
        }

        @Test
        void withoutRoutingConfigTheStartingRulesApply() {
            runner.run(ctx -> {
                RoutingProperties routing = ctx.getBean(RoutingProperties.class);
                assertThat(routing.defaultModel()).isEqualTo(OPUS);
                assertThat(routing.rules()).containsExactly(RoutingProperties.SHORT);
            });
        }

        @Test
        void startupFailsWhenARuleNamesAModelOutsideTheCatalog() {
            runner.withPropertyValues("gateway.routing.rules[0].name=cheap",
                            "gateway.routing.rules[0].model=claude-haiku-4")
                    .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                            .rootCause().hasMessageContaining("rule 'cheap' -> 'claude-haiku-4'"));
        }

        @Test
        void startupFailsWhenTheDefaultIsOutsideTheCatalog() {
            runner.withPropertyValues("gateway.routing.default-model=gpt-4o")
                    .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                            .rootCause().hasMessageContaining("default-model -> 'gpt-4o'"));
        }

        @Test
        void startupFailsOnABadRuleName() {
            runner.withPropertyValues("gateway.routing.rules[0].name=Short-Prompt",
                            "gateway.routing.rules[0].model=claude-haiku-5-5")
                    .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                            .rootCause().hasMessageContaining("must match"));
        }
    }
}
