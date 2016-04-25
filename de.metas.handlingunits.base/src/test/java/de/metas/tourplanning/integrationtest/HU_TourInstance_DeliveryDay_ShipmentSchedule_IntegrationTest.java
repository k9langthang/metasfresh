package de.metas.tourplanning.integrationtest;

/*
 * #%L
 * de.metas.handlingunits.base
 * %%
 * Copyright (C) 2015 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */


import java.math.BigDecimal;

import org.adempiere.model.InterfaceWrapperHelper;

import de.metas.handlingunits.model.I_M_ShipmentSchedule;
import de.metas.handlingunits.tourplanning.model.I_M_DeliveryDay_Alloc;
import de.metas.handlingunits.tourplanning.spi.impl.HUShipmentScheduleDeliveryDayHandlerTest;
import de.metas.tourplanning.model.I_M_DeliveryDay;

public class HU_TourInstance_DeliveryDay_ShipmentSchedule_IntegrationTest extends TourInstance_DeliveryDay_ShipmentSchedule_IntegrationTest
{
	@Override
	protected void afterInit()
	{
		super.afterInit();

		new de.metas.handlingunits.model.validator.Main().setupTourPlanning();
	}

	@Override
	protected boolean performTourPlanningRelevantChange(final de.metas.tourplanning.model.I_M_ShipmentSchedule shipmentSchedule)
	{
		super.performTourPlanningRelevantChange(shipmentSchedule);

		final I_M_ShipmentSchedule huShipmentSchedule = InterfaceWrapperHelper.create(shipmentSchedule, I_M_ShipmentSchedule.class);

		// Increase QryOrdered_LU by 10
		huShipmentSchedule.setQtyOrdered_LU(huShipmentSchedule.getQtyOrdered_LU().add(BigDecimal.valueOf(10)));

		// we expect that changing QtyOrdered_LU to be a releavant change for tour planning
		return true;
	}

	@Override
	protected I_M_DeliveryDay_Alloc assertDeliveryDayAlloc(final I_M_DeliveryDay deliveryDayExpected,
			final de.metas.tourplanning.model.I_M_ShipmentSchedule shipmentSchedule)
	{
		final de.metas.tourplanning.model.I_M_DeliveryDay_Alloc alloc = super.assertDeliveryDayAlloc(
				deliveryDayExpected,
				InterfaceWrapperHelper.create(shipmentSchedule, de.metas.tourplanning.model.I_M_ShipmentSchedule.class)
				);

		final I_M_DeliveryDay_Alloc huAlloc = InterfaceWrapperHelper.create(alloc, I_M_DeliveryDay_Alloc.class);
		final I_M_ShipmentSchedule huShipmentSchedule = InterfaceWrapperHelper.create(shipmentSchedule, I_M_ShipmentSchedule.class);
		HUShipmentScheduleDeliveryDayHandlerTest.assertEquals(huShipmentSchedule, huAlloc);

		return huAlloc;
	}

}
