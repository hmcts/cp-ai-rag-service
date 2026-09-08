package uk.gov.moj.cp.harness;

import uk.gov.moj.cp.ai.service.TokenUsage;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-model prices for turning measured {@link TokenUsage} into a cost per request.
 *
 * <p>Configured via {@code HARNESS_MODEL_PRICES}: comma-separated {@code label=input:output}
 * entries where the label matches the model's {@code HARNESS_LLM_DEPLOYMENTS} label and the two
 * numbers are USD per <b>1 million</b> tokens, e.g.
 * {@code HARNESS_MODEL_PRICES=gpt-51=1.25:10.00,claude-sonnet-4-6=3.00:15.00}.
 *
 * <p>Prices are deliberately configuration, not code: the correct figure depends on the platform
 * and deployment type actually targeted (Azure Global Standard vs EU Data Zone, Bedrock global vs
 * geo/regional +10%, Foundry CCU), so the harness only multiplies — it never assumes a price.
 * Models without a configured price report tokens but no cost.
 *
 * <p>Reasoning/thinking tokens are already included in {@code outputTokens} by every provider, so
 * the cost formula is simply {@code input×inPrice + output×outPrice}. Cached-input discounts are
 * not modelled (the harness sends uncached prompts).
 */
final class TokenCostTable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TokenCostTable.class);

    private static final String ENV_MODEL_PRICES = "HARNESS_MODEL_PRICES";
    private static final double ONE_MILLION = 1_000_000d;

    record ModelPrice(double inputPerMTok, double outputPerMTok) {
    }

    private final Map<String, ModelPrice> pricesByLabel;

    private TokenCostTable(final Map<String, ModelPrice> pricesByLabel) {
        this.pricesByLabel = pricesByLabel;
    }

    static TokenCostTable fromEnv() {
        final String raw = HarnessEnv.env(ENV_MODEL_PRICES, "").trim();
        final Map<String, ModelPrice> prices = new LinkedHashMap<>();
        if (!raw.isEmpty()) {
            for (final String entry : raw.split("[,;]")) {
                final String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                final int eq = trimmed.indexOf('=');
                final int colon = trimmed.indexOf(':', eq + 1);
                if (eq <= 0 || colon <= eq) {
                    LOGGER.warn("[cost] ignoring malformed {} entry '{}' — expected label=input:output (USD per 1M tokens)",
                            ENV_MODEL_PRICES, trimmed);
                    continue;
                }
                try {
                    final String label = trimmed.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    final double in = Double.parseDouble(trimmed.substring(eq + 1, colon).trim());
                    final double out = Double.parseDouble(trimmed.substring(colon + 1).trim());
                    prices.put(label, new ModelPrice(in, out));
                } catch (final NumberFormatException e) {
                    LOGGER.warn("[cost] ignoring malformed {} entry '{}' — {}", ENV_MODEL_PRICES, trimmed, e.getMessage());
                }
            }
        }
        if (prices.isEmpty()) {
            LOGGER.info("[cost] {} not set — token usage will be reported without cost", ENV_MODEL_PRICES);
        } else {
            LOGGER.info("[cost] model prices (USD per 1M tokens): {}", prices);
        }
        return new TokenCostTable(prices);
    }

    /** USD cost of one call, when a price is configured for the model label. */
    Optional<Double> costUsd(final String llmLabel, final TokenUsage usage) {
        if (usage == null || llmLabel == null) {
            return Optional.empty();
        }
        final ModelPrice price = pricesByLabel.get(llmLabel.trim().toLowerCase(Locale.ROOT));
        if (price == null) {
            return Optional.empty();
        }
        return Optional.of(usage.inputTokens() * price.inputPerMTok() / ONE_MILLION
                + usage.outputTokens() * price.outputPerMTok() / ONE_MILLION);
    }

    boolean hasPrice(final String llmLabel) {
        return llmLabel != null && pricesByLabel.containsKey(llmLabel.trim().toLowerCase(Locale.ROOT));
    }
}
