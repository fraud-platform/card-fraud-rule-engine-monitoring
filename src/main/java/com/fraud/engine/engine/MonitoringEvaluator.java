package com.fraud.engine.engine;

import com.fraud.engine.config.EvaluationConfig;
import com.fraud.engine.domain.Condition;
import com.fraud.engine.domain.DebugInfo;
import com.fraud.engine.domain.Decision;
import com.fraud.engine.domain.Rule;
import com.fraud.engine.domain.TransactionContext;
import com.fraud.engine.util.DecisionNormalizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@ApplicationScoped
public class MonitoringEvaluator {

    private static final Logger LOG = Logger.getLogger(MonitoringEvaluator.class);

    @Inject
    VelocityEvaluator velocityEvaluator;

    @Inject
    EvaluationConfig evaluationConfig;

    private record PendingMatchedRule(Rule rule, Decision.MatchedRule matchedRule, boolean requiresVelocity) {}

    public void evaluate(EvaluationContext context) {
        List<Rule> rules = context.getRulesToEvaluate();
        // OPT-09: Use array holder instead of AtomicReference for single-threaded lazy init
        Map<String, Object>[] evalContextHolder = new Map[1];
        Supplier<Map<String, Object>> evalContextSupplier = () -> {
            if (evalContextHolder[0] == null) {
                evalContextHolder[0] = context.evalContext() != null
                        ? context.evalContext()
                        : context.transaction().toEvaluationContext();
            }
            return evalContextHolder[0];
        };

        if (LOG.isDebugEnabled()) {
            LOG.debugf("MONITORING evaluation: %d rules to evaluate", rules.size());
        }

        List<PendingMatchedRule> pendingMatchedRules = new ArrayList<>();
        List<Rule> pendingVelocityRules = new ArrayList<>();
        Map<String, Decision.VelocityResult> replayVelocityCache = context.replayMode() ? new HashMap<>() : null;

        for (Rule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }

            if (LOG.isDebugEnabled()) {
                LOG.debugf("Evaluating rule: %s (%s)", rule.getId(), rule.getName());
            }

            boolean ruleMatched = evaluateRule(rule, context.transaction(), evalContextSupplier);
            if (context.isDebugEnabled()) {
                trackConditionEvaluations(rule, context.transaction(), evalContextSupplier.get(), ruleMatched, context.debugBuilder());
            }

            if (!ruleMatched) {
                continue;
            }

            if (rule.getVelocity() != null) {
                Decision.MatchedRule matchedRule = createMatchedRule(rule);
                pendingVelocityRules.add(rule);
                pendingMatchedRules.add(new PendingMatchedRule(rule, matchedRule, true));
                continue;
            }

            if (LOG.isDebugEnabled()) {
                LOG.debugf("Rule matched: %s (%s) - Action: %s",
                        rule.getId(), rule.getName(), rule.getAction());
            }

            Decision.MatchedRule matchedRule = createMatchedRule(rule);
            pendingMatchedRules.add(new PendingMatchedRule(rule, matchedRule, false));
        }

        // Batch velocity checks (big lever): turn N Redis RTTs into 1.
        Decision.VelocityResult[] velocityResults = new Decision.VelocityResult[0];
        if (!pendingVelocityRules.isEmpty() && !context.replayMode()) {
            velocityResults = velocityEvaluator.checkVelocityBatch(
                    context.transaction(),
                    pendingVelocityRules,
                    context.decision()
            );
        }

        List<Decision.MatchedRule> matchedRules = new ArrayList<>(pendingMatchedRules.size());
        int velocityIndex = 0;
        for (PendingMatchedRule pending : pendingMatchedRules) {
            Rule rule = pending.rule();
            Decision.MatchedRule matchedRule = pending.matchedRule();

            if (pending.requiresVelocity()) {
                Decision.VelocityResult velocityResult;
                if (context.replayMode()) {
                    velocityResult = velocityEvaluator.checkVelocityReadOnly(
                            context.transaction(), rule, context.decision(), replayVelocityCache);
                } else {
                    velocityResult = velocityResults[velocityIndex];
                }
                velocityIndex++;

                context.decision().addVelocityResult(rule.getId(), velocityResult);
                if (velocityResult.isExceeded()) {
                    matchedRule.setAction(rule.getVelocity().getAction());
                }
            }

            matchedRules.add(matchedRule);
        }

        context.decision().setMatchedRules(matchedRules);
        applyMonitoringDecision(context);

        if (LOG.isDebugEnabled()) {
            LOG.debugf("MONITORING evaluation complete: %d matched, decision: %s",
                    matchedRules.size(), context.decision().getDecision());
        }
    }

    private boolean evaluateRule(Rule rule, TransactionContext transaction, Supplier<Map<String, Object>> contextSupplier) {
        if (rule.getCompiledCondition() != null) {
            return rule.getCompiledCondition().matches(transaction);
        }
        Map<String, Object> context = contextSupplier.get();
        if (rule.getConditions() != null) {
            for (Condition condition : rule.getConditions()) {
                if (!condition.evaluate(context)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void applyMonitoringDecision(EvaluationContext context) {
        String providedDecision = context.transaction().getDecision();
        String normalized = DecisionNormalizer.normalizeDecisionValue(providedDecision);
        if (Decision.DECISION_APPROVE.equals(normalized) || Decision.DECISION_DECLINE.equals(normalized)) {
            context.decision().setDecision(normalized);
            return;
        }

        if (providedDecision != null && !providedDecision.isBlank()) {
            LOG.warnf("MONITORING decision invalid (%s); defaulting to APPROVE", providedDecision);
        } else {
            LOG.warn("MONITORING decision missing; defaulting to APPROVE");
        }
        context.decision().setDecision(Decision.DECISION_APPROVE);

        if (!context.replayMode() && Decision.MODE_NORMAL.equals(context.engineMode())) {
            context.decision().setEngineMode(Decision.MODE_DEGRADED);
        }
        if (context.decision().getEngineErrorCode() == null) {
            boolean hasValue = providedDecision != null && !providedDecision.isBlank();
            context.decision().setEngineErrorCode(hasValue ? "INVALID_DECISION" : "MISSING_DECISION");
            context.decision().setEngineErrorMessage(hasValue ? "MONITORING decision invalid" : "MONITORING requires decision");
        }
    }

    private Decision.MatchedRule createMatchedRule(Rule rule) {
        Decision.MatchedRule matched = new Decision.MatchedRule(
                rule.getId(),
                rule.getName(),
                rule.getAction()
        );
        matched.setPriority(rule.getPriority());
        matched.setRuleVersionId(rule.getRuleVersionId());
        matched.setRuleVersion(rule.getRuleVersion());
        return matched;
    }

    private void trackConditionEvaluations(Rule rule, TransactionContext transaction,
                                          Map<String, Object> context,
                                          boolean ruleMatched,
                                          DebugInfo.Builder debugBuilder) {
        List<Condition> conditions = rule.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            return;
        }

        for (Condition condition : conditions) {
            long evalStart = System.nanoTime();
            String fieldName = condition.getField();
            Object actualValue = context.get(fieldName);

            boolean matched = condition.evaluateValue(actualValue);
            long evalTime = System.nanoTime() - evalStart;

            String explanation = buildConditionExplanation(condition, actualValue, matched);

            DebugInfo.ConditionEvaluation eval = new DebugInfo.ConditionEvaluation(
                    rule.getId(),
                    rule.getName(),
                    fieldName,
                    condition.getOperatorEnum() != null ? condition.getOperatorEnum().name() : null,
                    condition.getValue(),
                    actualValue,
                    matched,
                    evalTime,
                    explanation
            );

            debugBuilder.addConditionEvaluation(eval);

            if (evaluationConfig != null && evaluationConfig.includeFieldValues) {
                debugBuilder.addFieldValue(fieldName, actualValue);
            }

            if (evaluationConfig != null && debugBuilder.getConditionEvaluationCount() >= evaluationConfig.maxConditionEvaluations) {
                break;
            }
        }
    }

    private String buildConditionExplanation(Condition condition, Object actualValue, boolean matched) {
        return String.format("%s(%s) %s %s = %s",
                condition.getField(),
                actualValue,
                getOperatorSymbol(condition.getOperatorEnum()),
                condition.getValue(),
                matched ? "true" : "false"
        );
    }

    private String getOperatorSymbol(Condition.Operator operator) {
        if (operator == null) {
            return "=";
        }
        return switch (operator) {
            case EQ -> "==";
            case NE -> "!=";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case IN -> "in";
            case NOT_IN -> "not in";
            case BETWEEN -> "between";
            case CONTAINS -> "contains";
            case STARTS_WITH -> "starts with";
            case ENDS_WITH -> "ends with";
            case REGEX -> "matches";
            case EXISTS -> "exists";
        };
    }
}
