/**
 *
 */
package de.metas.pricing.conditions.service;

import java.math.BigDecimal;

import de.metas.lang.Percent;
import de.metas.pricing.conditions.PricingConditionsBreakId;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.NonNull;
import lombok.Value;

/*
 * #%L
 * de.metas.business
 * %%
 * Copyright (C) 2018 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

/**
 * @author metas-dev <dev@metasfresh.com>
 *
 */
@Builder
@Value
public class CalculatePricingConditionsResult
{
	public static CalculatePricingConditionsResult discount(@NonNull final Percent discount)
	{
		if (discount.isZero())
		{
			return ZERO;
		}
		return builder().discount(discount).build();
	}

	public static final CalculatePricingConditionsResult ZERO = builder().discount(Percent.ZERO).build();

	@Default
	@NonNull
	Percent discount = Percent.ZERO;
	@Default
	int paymentTermId = -1;
	BigDecimal priceListOverride;
	BigDecimal priceStdOverride;
	BigDecimal priceLimitOverride;

	PricingConditionsBreakId pricingConditionsBreakId;

	@Default
	int basePricingSystemId = -1;
}
