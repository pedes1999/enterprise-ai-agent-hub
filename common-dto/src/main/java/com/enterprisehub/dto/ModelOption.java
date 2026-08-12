package com.enterprisehub.dto;

/** One selectable model for a vendor, as returned by that vendor's own model-listing API. label falls back to id when the vendor doesn't supply a separate display name. */
public record ModelOption(
        String id,
        String label
) {
}
