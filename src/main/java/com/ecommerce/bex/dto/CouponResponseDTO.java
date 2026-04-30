package com.ecommerce.bex.dto;

import com.ecommerce.bex.enums.DiscountType;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CouponResponseDTO(
        Long id,
        String code,
        int usageLimit,
        BigDecimal discountAmount,
        DiscountType type,
        LocalDateTime expiresAt
) implements Serializable {
}
