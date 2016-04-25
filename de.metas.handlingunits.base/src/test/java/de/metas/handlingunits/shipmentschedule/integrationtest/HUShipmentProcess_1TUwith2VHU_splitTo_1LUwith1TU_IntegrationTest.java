package de.metas.handlingunits.shipmentschedule.integrationtest;

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
import java.util.Arrays;
import java.util.List;

import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.util.Services;
import org.adempiere.util.lang.IMutable;
import org.adempiere.util.lang.Mutable;
import org.compiere.model.I_M_InOut;
import org.compiere.model.I_M_InOutLine;
import org.junit.Assert;

import de.metas.handlingunits.expectations.HUsExpectation;
import de.metas.handlingunits.expectations.ShipmentScheduleQtyPickedExpectations;
import de.metas.handlingunits.model.I_M_HU;
import de.metas.handlingunits.model.I_M_ShipmentSchedule;
import de.metas.handlingunits.model.X_M_HU;
import de.metas.handlingunits.model.X_M_HU_PI_Item;
import de.metas.inout.IInOutDAO;
import de.metas.shipping.interfaces.I_M_Package;

/**
 * Test case:
 * <ul>
 * <li>create a TU with 2 VHUs, each VHU with 10items
 * <li>assign each VHU to a different shipment schedule
 * <li>split TU to an LU with 1xTU with 2xVHUs, one VHU with 10items, one VHU with 5items
 * <li>assume: first VHU is assigned to first shipment schedule, second VHU to second shipment schedule
 * </ul>
 */
public class HUShipmentProcess_1TUwith2VHU_splitTo_1LUwith1TU_IntegrationTest extends AbstractHUShipmentProcessIntegrationTest
{
	@Override
	protected void step10_createShipmentSchedules()
	{
		shipmentSchedules = Arrays.asList(
				createShipmentSchedule(), // shipment schedule 0
				createShipmentSchedule() // shipment schedule 1
				);
	}

	@Override
	protected void step20_pickTUs()
	{
		//
		// Get shipment schedules
		final I_M_ShipmentSchedule shipmentSchedule1 = shipmentSchedules.get(0);
		final I_M_ShipmentSchedule shipmentSchedule2 = shipmentSchedules.get(1);

		//
		// Create initial TU
		final IMutable<I_M_HU> tu = new Mutable<>();
		final IMutable<I_M_HU> vhu1 = new Mutable<>();
		final IMutable<I_M_HU> vhu2 = new Mutable<>();
		//@formatter:off
		afterPick_HUExpectations = new HUsExpectation()
			//
			// TU
			.newHUExpectation()
				.capture(tu)
				.huStatus(X_M_HU.HUSTATUS_Picked)
				.huPI(piTU)
				.bPartner(bpartner)
				.bPartnerLocation(bpartnerLocation)
				.newHUItemExpectation()
					.itemType(X_M_HU_PI_Item.ITEMTYPE_Material)
					.huPIItem(piTU_Item)
					//
					// VHU 1
					.newIncludedVirtualHU()
						.capture(vhu1)
						.huStatus(X_M_HU.HUSTATUS_Picked)
						.newVirtualHUItemExpectation()
							.newItemStorageExpectation()
								.product(product).uom(productUOM).qty("10")
								.endExpectation()
							.endExpectation()
						.endExpectation()
					//
					// VHU 2
					.newIncludedVirtualHU()
						.capture(vhu2)
						.huStatus(X_M_HU.HUSTATUS_Picked)
						.newVirtualHUItemExpectation()
							.newItemStorageExpectation()
								.product(product).uom(productUOM).qty("10")
								.endExpectation()
							.endExpectation()
						.endExpectation()
					.endExpectation()
				.endExpectation();
		//@formatter:on
		afterPick_HUExpectations.createHUs();

		//
		// Assign VHU1 to shipmentSchedule1
		// Assign VHU2 to shipmentSchedule2
		//@formatter:off
		new ShipmentScheduleQtyPickedExpectations()
			.newShipmentScheduleQtyPickedExpectation()
				.shipmentSchedule(shipmentSchedule1)
				.noLU().tu(tu).vhu(vhu1).qtyPicked("10")
				.endExpectation()
			.newShipmentScheduleQtyPickedExpectation()
				.shipmentSchedule(shipmentSchedule2)
				.noLU().tu(tu).vhu(vhu2).qtyPicked("10")
				.endExpectation()
			.createM_ShipmentSchedule_QtyPickeds(helper.getContextProvider());
		//@formatter:on
	}

	@Override
	protected void step30_aggregateHUs()
	{
		//
		// Get Picked TU
		final I_M_HU tu = afterPick_HUExpectations.huExpectation(0)
				.getCapturedHU();
		//
		// Get shipment schedules
		final I_M_ShipmentSchedule shipmentSchedule1 = shipmentSchedules.get(0);
		final I_M_ShipmentSchedule shipmentSchedule2 = shipmentSchedules.get(1);

		//
		// Split our TU to an LU/TU with 15items
		final IMutable<I_M_HU> afterAggregation_LU = new Mutable<>();
		final IMutable<I_M_HU> splitHU_TU = new Mutable<>();
		final IMutable<I_M_HU> splitHU_vhu1 = new Mutable<>();
		final IMutable<I_M_HU> splitHU_vhu2 = new Mutable<>();
		final List<I_M_HU> splitHUs = helper.splitHUs()
				.setHUToSplit(tu)
				.setCUQty(new BigDecimal("15"))
				// LU
				.setLU_M_HU_PI_Item(piLU_Item)
				.setMaxLUToAllocate(new BigDecimal("1"))
				// TU
				.setTU_M_HU_PI_Item(piTU_Item)
				.setTUPerLU(new BigDecimal("1"))
				// CU
				.setCUProduct(product)
				.setCUPerTU(new BigDecimal("15"))
				.setCUUOM(productUOM)
				//
				.split();

		//
		// Validate split LU/TU/VHUs
		//@formatter:off
		afterAggregation_HUExpectations = new HUsExpectation()
			//
			// LU
			.newHUExpectation()
				.capture(afterAggregation_LU)
				.huPI(piLU)
				.huStatus(X_M_HU.HUSTATUS_Picked)
				.newHUItemExpectation(piLU_Item)
					//
					// TU
					.newIncludedHUExpectation()
						.capture(splitHU_TU)
						.huPI(piTU)
						.huStatus(X_M_HU.HUSTATUS_Picked)
						.newHUItemExpectation(piTU_Item)
							//
							// VHU 1
							.newIncludedVirtualHU()
								.capture(splitHU_vhu1)
								.newVirtualHUItemExpectation()
									.newItemStorageExpectation()
										.product(product).qty("10").uom(productUOM)
										.endExpectation()
									.endExpectation()
								.endExpectation()
							//
							// VHU 2
							.newIncludedVirtualHU()
								.capture(splitHU_vhu2)
								.newVirtualHUItemExpectation()
									.newItemStorageExpectation()
										.product(product).qty("5").uom(productUOM)
										.endExpectation()
									.endExpectation()
								.endExpectation()
							.endExpectation()
					.endExpectation()
				.endExpectation()
			.endExpectation()
			//
			.assertExpected("split HUs", splitHUs);
		//@formatter:on

		//
		// Validate Shipment Schedule assignments for splitHU's VHUs
		//@formatter:off
		afterAggregation_ShipmentScheduleQtyPickedExpectations = new ShipmentScheduleQtyPickedExpectations()
			.newShipmentScheduleQtyPickedExpectation()
				.shipmentSchedule(shipmentSchedule1)
				.lu(afterAggregation_LU).tu(splitHU_TU).vhu(splitHU_vhu1).qtyPicked("10")
				.endExpectation()
			.newShipmentScheduleQtyPickedExpectation()
				.shipmentSchedule(shipmentSchedule2)
				.lu(afterAggregation_LU).tu(splitHU_TU).vhu(splitHU_vhu2).qtyPicked("5")
				.endExpectation()
			.assertExpected("after split");
		//@formatter:on
	}

	@Override
	protected void step40_addAggregatedHUsToShipperTransportation()
	{
		super.step40_addAggregatedHUsToShipperTransportation();
	}

	@Override
	protected void step50_GenerateShipment()
	{
		super.step50_GenerateShipment();
	}

	@Override
	protected void step50_GenerateShipment_validateGeneratedShipments()
	{
		//
		// Get generated shipment
		Assert.assertEquals("Invalid generated shipments count", 1, generatedShipments.size());
		final I_M_InOut shipment = generatedShipments.get(0);

		//
		// Retrieve generated shipment lines
		// We expect to have 2 shipment lines, not because we have 2 shipment schedules, but because we have 2 order lines
		final List<I_M_InOutLine> shipmentLines = Services.get(IInOutDAO.class).retrieveLines(shipment);
		Assert.assertEquals("Invalid generated shipment lines count", 2, shipmentLines.size());
		final I_M_InOutLine shipmentLine1 = shipmentLines.get(0);
		final I_M_InOutLine shipmentLine2 = shipmentLines.get(1);

		//
		// Revalidate the ShipmentSchedule_QtyPicked expectations,
		// but this time, also make sure the M_InOutLine_ID is set
		//@formatter:off
		afterAggregation_ShipmentScheduleQtyPickedExpectations
			.shipmentScheduleQtyPickedExpectation(0)
				.inoutLine(shipmentLine1)
				.endExpectation()
			.shipmentScheduleQtyPickedExpectation(1)
				.inoutLine(shipmentLine2)
				.endExpectation()
			.assertExpected("after shipment generated");
		//@formatter:on
	}

	@Override
	protected void step50_GenerateShipment_validateShipperTransportationAfterShipment()
	{
		//
		// Get LUs Package
		Assert.assertEquals("Invalid generated LU packages count", 1, mpackagesForAggregatedHUs.size());
		final I_M_Package mpackage_LU = mpackagesForAggregatedHUs.get(0);

		//
		// Get generated shipment
		final I_M_InOut shipment = generatedShipments.get(0);

		//
		// Shipper Transportation: Make sure LU's M_Package is updated
		{
			InterfaceWrapperHelper.refresh(mpackage_LU);
			Assert.assertEquals("LU's M_Package does not have the right M_InOut_ID", shipment.getM_InOut_ID(), mpackage_LU.getM_InOut_ID());
		}
	}
}
