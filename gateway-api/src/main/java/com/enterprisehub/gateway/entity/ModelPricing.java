package com.enterprisehub.gateway.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What one model costs per million tokens, from a given date.
 *
 * Global reference data with no tenant column and no RLS, exactly like
 * {@link AgentDefinition}: a price list describes the outside world, is
 * identical for every tenant, and belongs to nobody.
 *
 * The table is append-only by convention -- a price change is a NEW row with
 * a later {@code effectiveFrom}, never an edit of the existing one. That is
 * what lets a run stay costed at the price that applied when it ran, which is
 * the only defensible way to report spend: re-pricing history every time a
 * vendor changes a rate would silently rewrite last quarter's numbers.
 *
 * Prices are BigDecimal, never double. Money in binary floating point
 * accumulates error that shows up as cents that don't reconcile, and 0.1 has
 * no exact double representation.
 */
@Entity
@Table(name = "model_pricing")
@Getter
@Setter
@NoArgsConstructor
public class ModelPricing {

    @Id
    @GeneratedValue
    private UUID id;

    /** ANTHROPIC / OPENAI / GEMINI / LOCAL -- reporting only; the rate is keyed by model. */
    @Column(nullable = false)
    private String provider;

    /** The exact model string sent to the provider, date suffix and all. */
    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "input_usd_per_mtok", nullable = false)
    private BigDecimal inputUsdPerMtok;

    @Column(name = "output_usd_per_mtok", nullable = false)
    private BigDecimal outputUsdPerMtok;

    /** A run is priced with the newest row whose effectiveFrom is at or before it. */
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom = Instant.now();

    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
