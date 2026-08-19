package com.enterprisehub.gateway.repository;

import com.enterprisehub.gateway.entity.ModelPricing;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModelPricingRepository extends JpaRepository<ModelPricing, UUID> {

    /**
     * Every price for this model that had already taken effect by {@code at},
     * newest first. Callers want only the first row -- see
     * {@link #findEffectivePrice} -- but this is expressed as a paged query
     * rather than a "current price" flag on the table for a reason: a flag
     * needs a cutover job to flip it, and would be wrong for exactly as long
     * as that job was late.
     *
     * Ordering by effectiveFrom DESC and taking one row makes a scheduled
     * price change work with no moving parts at all: insert next month's rate
     * today with a future effectiveFrom, and runs keep costing the old rate
     * until that instant passes, on their own.
     */
    @Query("select p from ModelPricing p where p.modelName = :modelName and p.effectiveFrom <= :at "
            + "order by p.effectiveFrom desc")
    List<ModelPricing> findPricesInEffect(String modelName, Instant at, Pageable pageable);

    /**
     * The single rate that applied to a run at {@code at}. Empty means this
     * model has no price on file -- which ExecutionCostCalculator reports as
     * UNPRICED and never as zero.
     */
    default Optional<ModelPricing> findEffectivePrice(String modelName, Instant at) {
        return findPricesInEffect(modelName, at, PageRequest.of(0, 1)).stream().findFirst();
    }
}
