package de.metas.handlingunits.attribute.storage.impl;

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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.mm.attributes.api.CurrentAttributeValueContextProvider;
import org.adempiere.mm.attributes.api.IAttributeDAO;
import org.adempiere.mm.attributes.spi.IAttributeValueCallout;
import org.adempiere.mm.attributes.spi.IAttributeValueContext;
import org.adempiere.util.Check;
import org.adempiere.util.Services;
import org.adempiere.util.lang.ObjectUtils;
import org.compiere.model.I_M_Attribute;
import org.compiere.model.I_M_AttributeValue;
import org.compiere.util.NamePair;
import org.compiere.util.Util;
import org.slf4j.Logger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import de.metas.handlingunits.attribute.IAttributeValue;
import de.metas.handlingunits.attribute.IAttributeValueListener;
import de.metas.handlingunits.attribute.IHUAttributesDAO;
import de.metas.handlingunits.attribute.IHUPIAttributesDAO;
import de.metas.handlingunits.attribute.exceptions.AttributeNotFoundException;
import de.metas.handlingunits.attribute.impl.NullAttributeValue;
import de.metas.handlingunits.attribute.propagation.IHUAttributePropagationContext;
import de.metas.handlingunits.attribute.propagation.IHUAttributePropagator;
import de.metas.handlingunits.attribute.propagation.IHUAttributePropagatorFactory;
import de.metas.handlingunits.attribute.propagation.impl.HUAttributePropagationContext;
import de.metas.handlingunits.attribute.storage.IAttributeStorage;
import de.metas.handlingunits.attribute.storage.IAttributeStorageFactory;
import de.metas.handlingunits.attribute.storage.IAttributeStorageListener;
import de.metas.handlingunits.attribute.strategy.IAttributeAggregationStrategy;
import de.metas.handlingunits.attribute.strategy.IAttributeSplitterStrategy;
import de.metas.handlingunits.attribute.strategy.IHUAttributeTransferStrategy;
import de.metas.handlingunits.exceptions.HUException;
import de.metas.handlingunits.model.X_M_HU_PI_Attribute;
import de.metas.handlingunits.storage.IHUStorageDAO;
import de.metas.logging.LogManager;

public abstract class AbstractAttributeStorage implements IAttributeStorage
{
	// Services
	protected final transient Logger logger = LogManager.getLogger(getClass());
	private final IHUAttributePropagatorFactory huAttributePropagatorFactory = Services.get(IHUAttributePropagatorFactory.class);
	private final IHUPIAttributesDAO huPIAttributesDAO = Services.get(IHUPIAttributesDAO.class);
	private final IAttributeDAO attributeDAO = Services.get(IAttributeDAO.class);

	// Factories and other DAOs
	private final IAttributeStorageFactory storageFactory;
	private final IHUAttributesDAO huAttributesDAO;
	private final IHUStorageDAO huStorageDAO;

	// Attributes
	private IndexedAttributeValues _indexedAttributeValues = IndexedAttributeValues.NULL;
	private final ReentrantLock _indexedAttributeValuesLock = new ReentrantLock();

	private final CompositeAttributeStorageListener listeners = new CompositeAttributeStorageListener();

	/** Listens on {@link IAttributeValue} events and forwards them to {@link #onAttributeValueChanged(IAttributeValueContext, IAttributeValue, Object, Object)}. */
	private final IAttributeValueListener attributeValueListener = new IAttributeValueListener()
	{
		@Override
		public void onValueChanged(final IAttributeValueContext attributeValueContext, final IAttributeValue attributeValue, final Object valueOld, final Object valueNew)
		{
			onAttributeValueChanged(attributeValueContext, attributeValue, valueOld, valueNew);
		}
	};

	/**
	 * Callouts invoker listener
	 *
	 * NOTE: we are keeping a hard reference here because listeners are registered weakly.
	 */
	private final CalloutAttributeStorageListener calloutAttributeStorageListener;

	/**
	 * Sort {@link IAttributeValue}s by DisplaySeqNo
	 */
	private static final Comparator<IAttributeValue> attributeValueSortBySeqNo = new Comparator<IAttributeValue>()
	{
		@Override
		public int compare(final IAttributeValue o1, final IAttributeValue o2)
		{
			// Sort by DisplaySeqNo
			final int displaySeqNoCmp = o1.getDisplaySeqNo() - o2.getDisplaySeqNo();
			if (displaySeqNoCmp != 0)
			{
				return displaySeqNoCmp;
			}

			// Fallback: sort by M_Attribute_ID (just to have a predictible order)
			final int attributeIdCmp = o1.getM_Attribute().getM_Attribute_ID() - o2.getM_Attribute().getM_Attribute_ID();
			return attributeIdCmp;
		}
	};

	public AbstractAttributeStorage(final IAttributeStorageFactory storageFactory)
	{
		super();

		Check.assumeNotNull(storageFactory, "storageFactory not null");
		this.storageFactory = storageFactory;
		huAttributesDAO = storageFactory.getHUAttributesDAO();
		huStorageDAO = storageFactory.getHUStorageDAO();

		calloutAttributeStorageListener = new CalloutAttributeStorageListener();
		listeners.addAttributeStorageListener(calloutAttributeStorageListener);
	}

	@Override
	public final String toString()
	{
		final ToStringHelper stringHelper = MoreObjects.toStringHelper(this);
		toString(stringHelper);
		return stringHelper
				.add("huAttributePropagatorFactory", huAttributePropagatorFactory)
				.add("storageFactory", storageFactory)
				.add("indexedAttributeValues", _indexedAttributeValues)
				.add("listeners", listeners)
				.add("attributeValueListener", attributeValueListener)
				.toString();
	}

	protected void toString(final ToStringHelper stringHelper)
	{
		// nothing on this level
	}

	@Override
	public final IAttributeStorageFactory getHUAttributeStorageFactory()
	{
		assertNotDisposed();

		return storageFactory;
	}

	protected final IHUAttributesDAO getHUAttributesDAO()
	{
		assertNotDisposed();

		return huAttributesDAO;
	}

	public final IHUStorageDAO getHUStorageDAO()
	{
		assertNotDisposed();

		return huStorageDAO;
	}

	protected final IHUPIAttributesDAO getHUPIAttributesDAO()
	{
		assertNotDisposed();

		return huPIAttributesDAO;
	}

	@Override
	public final void addListener(final IAttributeStorageListener listener)
	{
		assertNotDisposed();

		listeners.addAttributeStorageListener(listener);
	}

	@Override
	public final void removeListener(final IAttributeStorageListener listener)
	{
		// NOTE: we can accept removing a listener even if this storage is disposed,
		// because this method might be called as a reaction to a disposal event
		// assertNotDisposed();

		listeners.removeAttributeStorageListener(listener);
	}

	private final void onAttributeValueChanged(final IAttributeValueContext attributeValueContext, final IAttributeValue attributeValue, final Object valueOld, final Object valueNew)
	{
		assertNotDisposed();

		// TODO: check if attributeValue is in our list of attribute values
		listeners.onAttributeValueChanged(attributeValueContext, this, attributeValue, valueOld);
	}

	/**
	 * Load or creates initial {@link IAttributeValue}s.
	 *
	 * @return {@link IAttributeValue}s
	 */
	protected abstract List<IAttributeValue> loadAttributeValues();

	/**
	 * Set the inner {@link #attributeValuesRO}, build up the indexing maps and add {@link #attributeValueListener}.
	 *
	 * @param attributeValues
	 */
	private final void setInnerAttributeValues(final List<IAttributeValue> attributeValues)
	{
		_indexedAttributeValuesLock.lock();
		try
		{
			if (!_indexedAttributeValues.isNull())
			{
				throw new AdempiereException("Inner attributeValues list was already set."
						+ "\n Attribute Storage: " + this
						+ "\n New attribute values: " + attributeValues);
			}

			//logger.debug("Setting attribute values: {}", attributeValues, new Exception("trace"));

			final IndexedAttributeValues indexedAttributeValues = IndexedAttributeValues.of(attributeValues);
			indexedAttributeValues.addAttributeValueListener(attributeValueListener);

			_indexedAttributeValues = indexedAttributeValues;
		}
		finally
		{
			_indexedAttributeValuesLock.unlock();
		}
	}

	private final IndexedAttributeValues getIndexedAttributeValues()
	{
		_indexedAttributeValuesLock.lock();
		try
		{
			// Don't load attributes if we are generating them right in this moment
			// This case can happen when while generating the attributes some BL (e.g. callout) wants to access current attributes.
			if (_generateInitialAttributesRunning.get())
			{
				final HUException ex = new HUException("Accessing attribute storage while generating it's values is not allowed. Returning empty values."
						+ "\n Attribute Storage: " + this);
				logger.warn(ex.getLocalizedMessage(), ex);
				return _indexedAttributeValues;
			}

			// Load attributes if they where not loaded before
			if (_indexedAttributeValues.isNull())
			{
				final List<IAttributeValue> attributeValues = loadAttributeValues();
				setInnerAttributeValues(attributeValues);
			}

			return _indexedAttributeValues;
		}
		finally
		{
			_indexedAttributeValuesLock.unlock();
		}
	}

	private final IndexedAttributeValues getIndexedAttributeValuesNoLoad()
	{
		// NOTE: even though we are not loading them, at least we want to make sure there is no loading in progress,
		// because that would produce unpredictable results
		_indexedAttributeValuesLock.lock();
		try
		{
			return _indexedAttributeValues;
		}
		finally
		{
			_indexedAttributeValuesLock.unlock();
		}
	}

	@Override
	public final List<IAttributeValue> getAttributeValues()
	{
		assertNotDisposed();

		return getIndexedAttributeValues().getAttributeValues();
	}

	/**
	 * Gets current {@link IAttributeValue}s.
	 *
	 * Compared with {@link #getAttributeValues()} which is also loading them if needed, this method is just returning current ones, loaded or not.
	 *
	 * @return current attribute values
	 */
	protected final List<IAttributeValue> getAttributeValuesCurrent()
	{
		return getIndexedAttributeValuesNoLoad().getAttributeValues();
	}

	@Override
	public final IAttributeValue getAttributeValue(final I_M_Attribute attribute)
	{
		// assertNotDisposed(); // checked in next called method

		final IAttributeValue attributeValue = getAttributeValueOrNull(attribute);
		if (NullAttributeValue.isNull(attributeValue))
		{
			throw new AttributeNotFoundException(attribute, this);
		}
		return attributeValue;
	}

	/** @return {@link IAttributeValue} or {@link NullAttributeValue#instance} */
	private IAttributeValue getAttributeValueOrNull(final I_M_Attribute attribute)
	{
		assertNotDisposed();
		Check.assumeNotNull(attribute, "attribute not null");

		final int attributeId = attribute.getM_Attribute_ID();
		final IAttributeValue attributeValue = getIndexedAttributeValues().getAttributeValueOrNull(attributeId);
		if (attributeValue == null)
		{
			return NullAttributeValue.instance;
		}

		return attributeValue;
	}

	@Override
	public final boolean hasAttribute(final I_M_Attribute attribute)
	{
		assertNotDisposed();
		Check.assumeNotNull(attribute, "attribute not null");

		final int attributeId = attribute.getM_Attribute_ID();
		return getIndexedAttributeValues().hasAttribute(attributeId);
	}

	@Override
	public final Collection<I_M_Attribute> getAttributes()
	{
		assertNotDisposed();
		return getIndexedAttributeValues().getAttributes();
	}

	@Override
	public final I_M_Attribute getAttributeByIdIfExists(final int attributeId)
	{
		assertNotDisposed();

		if (attributeId <= 0)
		{
			return null;
		}

		return getIndexedAttributeValues().getAttributeOrNull(attributeId);
	}

	@Override
	public final I_M_Attribute getAttributeByValueKeyOrNull(final String attributeValueKey)
	{
		assertNotDisposed();

		final IndexedAttributeValues indexedAttributeValues = getIndexedAttributeValues();
		if (!indexedAttributeValues.hasAttributes())
		{
			throw new AttributeNotFoundException(attributeValueKey, this);
		}

		return indexedAttributeValues.getAttributeByValueKeyOrNull(attributeValueKey);
	}

	@Override
	public final void generateInitialAttributes(final Map<I_M_Attribute, Object> defaultAttributesValue)
	{
		assertNotDisposed();

		// Assume there were no attributes generated yet
		if (!getIndexedAttributeValuesNoLoad().isNull())
		{
			throw new AdempiereException("Cannot generate attributes because they were already generated: " + this);
		}

		final IAttributeValueContext attributesCtx = getCurrentPropagationContextOrNull();

		//
		// Generate initial attributes
		final List<IAttributeValue> attributeValues;
		final boolean generateInitialAttributesAlreadyRunning = _generateInitialAttributesRunning.getAndSet(true);
		Check.assume(!generateInitialAttributesAlreadyRunning, "Internal error: generateInitialAttributes is already running for {}", this);
		try
		{
			attributeValues = generateAndGetInitialAttributes(attributesCtx, defaultAttributesValue);
		}
		finally
		{
			_generateInitialAttributesRunning.set(false);
		}
		setInnerAttributeValues(attributeValues);

		//
		// Notify listeners
		// => callouts will be triggered
		if (attributeValues != null && !attributeValues.isEmpty())
		{
			for (final IAttributeValue attributeValue : attributeValues)
			{
				listeners.onAttributeValueCreated(attributesCtx, this, attributeValue);
			}
		}

		//
		// Notify parent that a new child attributes storage was added
		final IAttributeStorage parentAttributeStorage = getParentAttributeStorage();
		if (!NullAttributeStorage.instance.equals(parentAttributeStorage))
		{
			parentAttributeStorage.onChildAttributeStorageAdded(this);
		}
	}

	private final AtomicBoolean _generateInitialAttributesRunning = new AtomicBoolean(false);

	protected abstract List<IAttributeValue> generateAndGetInitialAttributes(final IAttributeValueContext attributesCtx, Map<I_M_Attribute, Object> defaultAttributesValue);

	@Override
	public Object getValue(final I_M_Attribute attribute)
	{
		// assertNotDisposed(); // checked in next called method

		final IAttributeValue value = getAttributeValue(attribute);
		return value.getValue();
	}

	@Override
	public BigDecimal getValueAsBigDecimal(final I_M_Attribute attribute)
	{
		// assertNotDisposed(); // checked in next called method

		final IAttributeValue value = getAttributeValue(attribute);
		return value.getValueAsBigDecimal();
	}

	@Override
	public int getValueAsInt(final I_M_Attribute attribute)
	{
		// assertNotDisposed(); // checked in next called method

		final IAttributeValue value = getAttributeValue(attribute);
		return value.getValueAsInt();
	}

	@Override
	public String getValueName(final I_M_Attribute attribute)
	{
		assertNotDisposed();

		Check.assumeNotNull(attribute, "attribute not null");
		final Object value = getValue(attribute);
		Check.assumeNotNull(value, "attributeValue not null");
		final String valueStr = value.toString();

		//
		// Do not allow the M_AttributeValue to be null in this case. We're assuming that there are database entries for predefined values already.
		// If you're writing automatic tests, you'll have to make some entries.
		final I_M_AttributeValue attributeValue = attributeDAO.retrieveAttributeValueOrNull(attribute, valueStr);
		Check.assumeNotNull(attributeValue, "M_AttributeValue was found for M_Attribute={}, M_Attribute.Value={}", attribute, valueStr);

		return attributeValue.getName();
	}

	@Override
	public Object getValueInitial(final I_M_Attribute attribute)
	{
		// assertNotDisposed(); // checked in next called method

		final IAttributeValue value = getAttributeValue(attribute);
		return value.getValueInitial();
	}

	@Override
	public BigDecimal getValueInitialAsBigDecimal(final I_M_Attribute attribute)
	{
		// assertNotDisposed(); // checked in next called method

		final IAttributeValue value = getAttributeValue(attribute);
		return value.getValueInitialAsBigDecimal();
	}

	private IHUAttributePropagationContext createPropagationContext(final I_M_Attribute attribute, final IHUAttributePropagator propagator)
	{
		final IHUAttributePropagationContext currentPropagationContext = getCurrentPropagationContextOrNull();
		if (currentPropagationContext == null)
		{
			return new HUAttributePropagationContext(this, propagator, attribute);
		}

		return currentPropagationContext.cloneForPropagation(this, attribute, propagator);
	}

	/**
	 * <b>Note: This is only package for testing purposes. NEVER call directly.</b><br>
	 *
	 * @return {@link IHUAttributePropagationContext} available or <code>null</code> if propagation wasn't started yet
	 */
	@VisibleForTesting
	/* package */final IHUAttributePropagationContext getCurrentPropagationContextOrNull()
	{
		final IAttributeValueContext attributesContext = CurrentAttributeValueContextProvider.getCurrentAttributesContextOrNull();
		return toHUAttributePropagationContext(attributesContext);
	}

	private static final IHUAttributePropagationContext toHUAttributePropagationContext(final IAttributeValueContext attributesContext)
	{
		Check.assumeInstanceOfOrNull(attributesContext, IHUAttributePropagationContext.class, "attributesContext");
		final IHUAttributePropagationContext huAttributesContext = (IHUAttributePropagationContext)attributesContext;
		return huAttributesContext;
	}

	/**
	 * <b>Note: This is only package for testing purposes. NEVER call directly.</b><br>
	 * <br>
	 * Set propagation context in {@link CurrentAttributeValueContextProvider}. This method will validate that the new context will have the old registered context as a parent.<br>
	 * However, if <code>validateParentContext=false</code> (rollback), then don't check for parent, because we assume that when first setting it we have already checked.
	 *
	 * @param huAttributePropagationContext
	 * @param validateParentContext
	 * @return
	 */
	@VisibleForTesting
	/* package */final IHUAttributePropagationContext setCurrentPropagationContext(final IHUAttributePropagationContext huAttributePropagationContext, final boolean validateParentContext)
	{
		final IAttributeValueContext attributesContextOld = CurrentAttributeValueContextProvider.setCurrentAttributesContext(huAttributePropagationContext);
		final IHUAttributePropagationContext huAttributePropagationContextOld = toHUAttributePropagationContext(attributesContextOld);

		//
		// This means that we're not rolling back propagation context, but setting it for the first time
		if (validateParentContext)
		{
			// currentPropagationContextBackup might be null, but then propagationContext should also have parent==null
			Check.errorIf(!Util.same(huAttributePropagationContext.getParent(), huAttributePropagationContextOld),
					"{} is not a parent of {}",
					huAttributePropagationContextOld, huAttributePropagationContext);
		}

		return huAttributePropagationContextOld;
	}

	@Override
	public final void setValueNoPropagate(final I_M_Attribute attribute, final Object value)
	{
		assertNotDisposed();

		//
		// Get NoPropagation propagator
		final String noPropagation = X_M_HU_PI_Attribute.PROPAGATIONTYPE_NoPropagation;
		final IHUAttributePropagator noPropagationPropagator = huAttributePropagatorFactory.getPropagator(noPropagation);

		//
		// Create propagation context with NoPropagation propagator & set internal value
		final IHUAttributePropagationContext propagationContext = createPropagationContext(attribute, noPropagationPropagator);
		setValue(propagationContext, value);
	}

	/**
	 * Note: Order of setting values is important here:
	 * <ol>
	 * <li>Set internal storage value and propagate in normal direction.</li>
	 * <li>Afterwards, propagate in opposite direction if possible without re-setting the internal value.</li>
	 * </ol>
	 */
	@Override
	public final void setValue(final I_M_Attribute attribute, final Object value)
	{
		assertNotDisposed();

		final IHUAttributePropagator attributePropagator = huAttributePropagatorFactory.getPropagator(this, attribute);
		final IHUAttributePropagationContext propagationContext = createPropagationContext(attribute, attributePropagator);

		//
		// Note that this variable sort-of means:
		// * If pushing in reverse, keep doing so, but don't change direction again
		// * If not pushing in reverse, then push in normal direction first, then in reverse
		boolean pushingDirectionReverse = false;

		//
		// Find pushing direction based on propagation context hierarchy
		final IHUAttributePropagator lastPropagatorForAttribute = propagationContext.getLastPropagatorOrNull(attribute);
		if (lastPropagatorForAttribute != null
				&& attributePropagator.getReversalPropagationType()
						.equals(lastPropagatorForAttribute.getPropagationType())) // if we're propagating in reverse
		{
			pushingDirectionReverse = true;
		}

		//
		// Propagate in the normal direction of the attribute (only if we're not currently pushing in opposite direction)
		if (!pushingDirectionReverse)
		{
			setValue(propagationContext, value);
		}

		//
		// There's no use to propagate in reverse if we don't have a no-propagation propagator
		if (!isPropagable(getAttributeValue(attribute)))
		{
			return;
		}

		//
		// On external input, propagate in the reverse direction of the attribute
		final IHUAttributePropagator reversalAttributePropagator = huAttributePropagatorFactory.getReversalPropagator(attributePropagator);
		final IHUAttributePropagationContext reversalPropagationContext = createPropagationContext(attribute, reversalAttributePropagator);
		reversalPropagationContext.setUpdateStorageValue(pushingDirectionReverse); // do not update storage inner value when propagating in reverse the first time (was set right before)
		setValue(reversalPropagationContext, value);
	}

	@Override
	public final NamePair setValueToNull(final I_M_Attribute attribute)
	{
		final IAttributeValue attributeValue = getAttributeValue(attribute);
		final NamePair nullValue = attributeValue.getNullAttributeValue();
		setValue(attribute, nullValue);

		return nullValue;
	}

	/**
	 * Set attribute's value and propagate to its parent/child attribute storages using the attribute propagator specified in the context.
	 * <p>
	 * Note: not only the actual propagation, but also the set-invocation to <code>this</code> storage is the propagator's job.
	 *
	 * @param propagationContext
	 * @param value
	 *
	 * @throws AttributeNotFoundException if given attribute was not found or is not supported
	 */
	private final void setValue(final IHUAttributePropagationContext propagationContext, final Object value)
	{
		Check.assumeNotNull(propagationContext, "propagationContext not null for " + this);

		//
		// Avoid recursion in case value was already updated somewhere before this invocation
		if (propagationContext.isValueUpdatedBefore())
		{
			logger.debug("ALREADY UPDATED: Skipping attribute value propagation for Value={}, Attribute={}, this={}, propagationContext={}",
					new Object[] { value, propagationContext.getAttribute(), this, propagationContext });
			return;
		}

		final Object valueOld = getValue(propagationContext.getAttribute());
		if (Check.equals(valueOld, value)
				//
				// We only wish to skip propagating when we're not updating internal storage value (because that happens in reversal and we want reversals)
				//
				&& propagationContext.isUpdateStorageValue())
		{
			// Nothing changed, it's pointless to set it again and call the propagator
			logger.debug("SAME VALUE: Skipping attribute value propagation for Value={}, =Attribute{}, this={}, propagationContext={}",
					value, propagationContext.getAttribute(), this, propagationContext );
			return;
		}

		// Make sure that while we recurse into a propagator, we have that propagator's context as our own 'currentPropagationContext'.
		// However, when we return from that invocation, we resort to our former context (see finally block).
		final IHUAttributePropagationContext currentPropagationContextBackup = setCurrentPropagationContext(propagationContext, true); // validateParentContext

		try
		{
			// Mark that in this context we have set the attribute value
			// We use this mark to avoid recursion
			propagationContext.setValueUpdateInvocation();

			final IHUAttributePropagator propagator = propagationContext.getPropagator();

			logger.debug("PROPAGATING with propagator={}: Setting Value={}, Attribute={}, this={}, propagationContext={}",
					propagator, value, propagationContext.getAttribute(), this, propagationContext );
			propagator.propagateValue(propagationContext, this, value);
		}
		finally
		{
			// Restore to old context
			setCurrentPropagationContext(currentPropagationContextBackup, false); // validateParentContext
		}
	}

	@Override
	public String getPropagationType(final I_M_Attribute attribute)
	{
		assertNotDisposed();

		final IAttributeValue attributeValue = getAttributeValueOrNull(attribute);
		return attributeValue.getPropagationType();
	}

	@Override
	public IAttributeAggregationStrategy retrieveAggregationStrategy(final I_M_Attribute attribute)
	{
		assertNotDisposed();

		final IAttributeValue attributeValue = getAttributeValueOrNull(attribute);
		return attributeValue.retrieveAggregationStrategy();
	}

	@Override
	public IAttributeSplitterStrategy retrieveSplitterStrategy(final I_M_Attribute attribute)
	{
		assertNotDisposed();

		final IAttributeValue attributeValue = getAttributeValueOrNull(attribute);
		return attributeValue.retrieveSplitterStrategy();
	}

	@Override
	public IHUAttributeTransferStrategy retrieveTransferStrategy(final I_M_Attribute attribute)
	{
		assertNotDisposed();

		final IAttributeValue attributeValue = getAttributeValueOrNull(attribute);
		return attributeValue.retrieveTransferStrategy();
	}

	@Override
	public boolean isPropagatedValue(final I_M_Attribute attribute)
	{
		assertNotDisposed();

		final IAttributeValue attributeValue = getAttributeValueOrNull(attribute);

		final String attributePropagationType = attributeValue.getPropagationType();

		if (X_M_HU_PI_Attribute.PROPAGATIONTYPE_NoPropagation.equals(attributePropagationType))
		{
			return false;
		}
		else if (X_M_HU_PI_Attribute.PROPAGATIONTYPE_BottomUp.equals(attributePropagationType))
		{
			return isPropagatedFromChildren(this, attribute);
		}
		else if (X_M_HU_PI_Attribute.PROPAGATIONTYPE_TopDown.equals(attributePropagationType))
		{
			return isPropagatedFromParents(this, attribute);
		}
		else
		{
			throw new AdempiereException("Not supported attribute propagation type: " + attributePropagationType);
		}
	}

	/**
	 * Helper method which checks if given attribute can <b>AND</b> actually it is propagated from children.
	 *
	 * @param attributeStorage
	 * @param attribute
	 * @return true if given attribute is propagated from children
	 */
	private static boolean isPropagatedFromChildren(final IAttributeStorage attributeStorage, final I_M_Attribute attribute)
	{
		final Collection<IAttributeStorage> childAttributeStorages = attributeStorage.getChildAttributeStorages();
		Check.assumeNotNull(childAttributeStorages, "childAttributeSets not null");
		if (childAttributeStorages.isEmpty())
		{
			return false;
		}

		for (final IAttributeStorage childAttributeStorage : childAttributeStorages)
		{
			final String childAttributePropagationType = childAttributeStorage.getPropagationType(attribute);
			if (X_M_HU_PI_Attribute.PROPAGATIONTYPE_BottomUp.equals(childAttributePropagationType))
			{
				return true;
			}

			if (isPropagatedFromChildren(childAttributeStorage, attribute))
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * Helper method which checks if given attribute can <b>AND</b> actually it is propagated from it's parent.
	 *
	 * @param attributeStorage
	 * @param attribute
	 * @return true if given attribute is propagated from it's parent
	 */
	private static boolean isPropagatedFromParents(final IAttributeStorage attributeStorage, final I_M_Attribute attribute)
	{
		final IAttributeStorage parentAttributeStorage = attributeStorage.getParentAttributeStorage();
		Check.assumeNotNull(parentAttributeStorage, "parentAttributeSet not null");
		if (NullAttributeStorage.instance.equals(parentAttributeStorage))
		{
			return false;
		}

		final String parentAttributePropagationType = parentAttributeStorage.getPropagationType(attribute);
		if (X_M_HU_PI_Attribute.PROPAGATIONTYPE_TopDown.equals(parentAttributePropagationType))
		{
			return true;
		}

		if (isPropagatedFromParents(parentAttributeStorage, attribute))
		{
			return true;
		}

		return false;
	}

	@Override
	public IAttributeValueCallout getAttributeValueCallout(final I_M_Attribute attribute)
	{
		assertNotDisposed();

		final IAttributeValue attributeValue = getAttributeValue(attribute);
		return attributeValue.getAttributeValueCallout();
	}

	@Override
	public String getAttributeValueType(final I_M_Attribute attribute)
	{
		assertNotDisposed();

		final IAttributeValue attributeValue = getAttributeValue(attribute);
		return attributeValue.getAttributeValueType();
	}

	/**
	 * Method called when this storage is notified that a child was added.
	 *
	 * NOTE: in case our attribute storage implementation is caching the child storages, this is the place where we need to adjust our internal child storages cache
	 *
	 * @param childAttributeStorage
	 */
	protected abstract void addChildAttributeStorage(final IAttributeStorage childAttributeStorage);

	/**
	 * Method called when this storage is notified that a child was removed.
	 *
	 * NOTE: in case our attribute storage implementation is caching the child storages, this is the place where we need to adjust our internal child storages cache
	 *
	 * @param childAttributeStorage
	 */
	protected abstract IAttributeStorage removeChildAttributeStorage(final IAttributeStorage childAttributeStorage);

	@Override
	public final void onChildAttributeStorageAdded(final IAttributeStorage childAttributeStorage)
	{
		assertNotDisposed();

		//
		// Add child storage to our inner list
		// (do this before you call "pushDown")
		addChildAttributeStorage(childAttributeStorage);

		//
		// Propagate from given children to all it's parents (... and children too)
		if (childAttributeStorage != null)
		{
			childAttributeStorage.pushUp();
		}
	}

	@Override
	public void onChildAttributeStorageRemoved(final IAttributeStorage childAttributeStorageRemoved)
	{
		//
		// Ask child to pushUp null values for it's attributes
		childAttributeStorageRemoved.pushUpRollback();

		//
		// Remove child storage from our inner list
		// (do this before you call "pushDown")
		removeChildAttributeStorage(childAttributeStorageRemoved);
	}

	@Override
	public void pushUp()
	{
		assertNotDisposed();

		for (final IAttributeValue attributeValue : getAttributeValues())
		{
			final boolean pushNullValue = false;
			pushAttributePropagation(attributeValue, X_M_HU_PI_Attribute.PROPAGATIONTYPE_BottomUp, pushNullValue);
		}
	}

	@Override
	public void pushUpRollback()
	{
		assertNotDisposed();

		for (final IAttributeValue attributeValue : getAttributeValues())
		{
			final boolean pushNullValue = true;
			pushAttributePropagation(attributeValue, X_M_HU_PI_Attribute.PROPAGATIONTYPE_BottomUp, pushNullValue);
		}
	}

	/**
	 * Push down attributes: force propagating attributes from this storage to all it's child storages
	 */
	@Override
	public void pushDown()
	{
		assertNotDisposed();

		for (final IAttributeValue attributeValue : getAttributeValues())
		{
			final boolean pushNullValue = false;
			pushAttributePropagation(attributeValue, X_M_HU_PI_Attribute.PROPAGATIONTYPE_TopDown, pushNullValue);
		}
	}

	/**
	 * Check if given attribute can be propagated as desired.
	 *
	 * NOTE: this method is not checking what kind of attribute is on parent level or on children level, that's the reason why it's static
	 *
	 * @param attributeValue attribute
	 * @return <code>true</code> if the given attribute's propagation type is not <code>NONE</code> and if it is equal to the given <code>desiredPropagationType</code>.
	 */
	private static final boolean isPropagable(final IAttributeValue attributeValue)
	{
		Check.assumeNotNull(attributeValue, "attributeValue not null");

		//
		// Check if this attribute is propagated
		//
		// NOTE: we are not calling isPropagatedValue(attribute) method because at this moment it can be that not all information are available
		// so we just check if propagation type is not None
		final String attributePropagationType = attributeValue.getPropagationType();
		if (X_M_HU_PI_Attribute.PROPAGATIONTYPE_NoPropagation.equals(attributePropagationType))
		{
			return false;
		}
		return true;
	}

	/**
	 * Propagate attribute's value:
	 * <ul>
	 * <li>UP to it's parent, if <code>desiredPropagationType</code> is {@link X_M_HU_PI_Attribute#PROPAGATIONTYPE_BottomUp}
	 * <li>DOWN to it's children, if <code>desiredPropagationType</code> is {@link X_M_HU_PI_Attribute#PROPAGATIONTYPE_TopDown}
	 * </ul>
	 * <p>
	 * Note that the method will do nothing if {@link #isPropagable(IAttributeValue, String)} returns <code>false</code>.
	 *
	 * @param attributeValue attribute/value to be propagated (see {@link IAttributeValue#getValue()}).
	 * @param desiredPropagationType propagation direction (TopDown, BottomUp)
	 * @param pushNullValue if true, then don't propagate the actual value of the given <code>attributeValue</code>, but propagate <code>null</code> instead (behave like a rollback)
	 *
	 * @see IHUAttributePropagatorFactory#getReversalPropagator(String)
	 */
	private void pushAttributePropagation(final IAttributeValue attributeValue,
			final String desiredPropagationType,
			final boolean pushNullValue)
	{
		//
		// Check if attribute is propagable
		if (!isPropagable(attributeValue))
		{
			return;
		}

		final I_M_Attribute attribute = attributeValue.getM_Attribute();

		final IHUAttributePropagator propagator = huAttributePropagatorFactory.getPropagator(desiredPropagationType);

		final Object value;
		if (pushNullValue)
		{
			value = null;
		}
		else
		{
			value = attributeValue.getValue();
		}

		// If we are asked to push null value instead of actual attribute's value (this is the case when we are preparing to remove this HU from it's parent)
		// we shall not allow the propagator to actually change this attribute's value to null because there wasn't such a change
		final boolean updateStorageValue = !pushNullValue;

		final IHUAttributePropagationContext propagationContext = createPropagationContext(attribute, propagator);
		propagationContext.setUpdateStorageValue(updateStorageValue);
		propagator.propagateValue(propagationContext, this, value);
	}

	@Override
	public boolean isReadonlyUI(final IAttributeValueContext ctx, final I_M_Attribute attribute)
	{
		assertNotDisposed();

		// Instance Attributes are always readonly
		if (!attribute.isInstanceAttribute())
		{
			return true;
		}

		final IAttributeValueCallout callout = getAttributeValueCallout(attribute);
		if (callout.isAlwaysEditableUI(ctx, this, attribute))
		{
			return false; // never readonly
		}

		// Check if attribute is configured to be readonly
		final IAttributeValue attributeValue = getAttributeValue(attribute);
		if (attributeValue.isReadonlyUI())
		{
			return true;
		}

		//
		// The callout of this attribute value has the right to decide whether or not the attribute shall be readonly
		if (callout.isReadonlyUI(ctx, this, attribute))
		{
			return true;
		}

		// Propagated attributes are readonly
		if (isPropagatedValue(attribute))
		{
			return true;
		}

		// We assume attribute is editable (readonlyUI=false)
		return false;
	}

	protected final Object getDefaultAttributeValue(final Map<I_M_Attribute, Object> defaultAttributesValue, final I_M_Attribute attribute)
	{
		if (defaultAttributesValue == null || defaultAttributesValue.isEmpty())
		{
			return null;
		}

		Check.assumeNotNull(attribute, "attribute not null");

		// Check for given attribute directly
		if (defaultAttributesValue.containsKey(attribute))
		{
			return defaultAttributesValue.get(attribute);
		}

		//
		// Fallback: check if we can find the attribute by attribute
		final int attributeId = attribute.getM_Attribute_ID();
		for (final Map.Entry<I_M_Attribute, Object> e : defaultAttributesValue.entrySet())
		{
			final I_M_Attribute currentAttribute = e.getKey();
			if (currentAttribute == null)
			{
				continue;
			}
			if (attributeId == currentAttribute.getM_Attribute_ID())
			{
				return e.getValue();
			}
		}

		//
		// No default value found for given attribute
		return null;
	}

	@Override
	public BigDecimal getStorageQtyOrZERO()
	{
		return BigDecimal.ZERO;
	}

	@Override
	public boolean isVirtual()
	{
		return false;
	}

	@Override
	public boolean isNew(final I_M_Attribute attribute)
	{
		assertNotDisposed();

		final IAttributeValue attributeValue = getAttributeValue(attribute);
		return attributeValue.isNew();
	}

	@Override
	public boolean assertNotDisposed()
	{
		// nothing at this level
		return true; // not disposed
	}

	/**
	 * Fires {@link IAttributeStorageListener#onAttributeStorageDisposed(IAttributeStorage)} event.
	 *
	 * Please make sure you are calling this method BEFORE clearing the listeners.
	 */
	protected final void fireAttributeStorageDisposed()
	{
		listeners.onAttributeStorageDisposed(this);
	}

	private static final class IndexedAttributeValues
	{
		public static final IndexedAttributeValues NULL = new IndexedAttributeValues();

		public static final IndexedAttributeValues of(final List<IAttributeValue> attributeValues)
		{
			if (attributeValues == null || attributeValues.isEmpty())
			{
				return NULL;
			}
			return new IndexedAttributeValues(attributeValues);
		}

		private final List<IAttributeValue> attributeValuesRO;
		private final Map<Integer, I_M_Attribute> attributeId2attributeRO;
		private final Map<Integer, IAttributeValue> attributeId2attributeValueRO;

		/** Null constructor */
		private IndexedAttributeValues()
		{
			super();
			attributeValuesRO = ImmutableList.of();
			attributeId2attributeRO = ImmutableMap.of();
			attributeId2attributeValueRO = ImmutableMap.of();
		}

		private IndexedAttributeValues(final List<IAttributeValue> attributeValues)
		{
			super();
			final List<IAttributeValue> attributeValuesList = new ArrayList<IAttributeValue>(attributeValues);
			Collections.sort(attributeValuesList, AbstractAttributeStorage.attributeValueSortBySeqNo);

			final Map<Integer, I_M_Attribute> attributeId2attribute = new HashMap<Integer, I_M_Attribute>(attributeValuesList.size());
			final Map<Integer, IAttributeValue> attributeId2attributeValue = new HashMap<Integer, IAttributeValue>(attributeValuesList.size());
			for (final IAttributeValue attributeValue : attributeValuesList)
			{
				final I_M_Attribute attribute = attributeValue.getM_Attribute();
				final int attributeId = attribute.getM_Attribute_ID();

				attributeId2attribute.put(attributeId, attribute);
				attributeId2attributeValue.put(attributeId, attributeValue);

				// attributeValue.addAttributeValueListener(attributeValueListener);
			}

			attributeValuesRO = ImmutableList.copyOf(attributeValuesList);
			attributeId2attributeRO = ImmutableMap.copyOf(attributeId2attribute);
			attributeId2attributeValueRO = ImmutableMap.copyOf(attributeId2attributeValue);
		}

		@Override
		public String toString()
		{
			return ObjectUtils.toString(this);
		}

		public void addAttributeValueListener(final IAttributeValueListener attributeValueListener)
		{
			for (final IAttributeValue attributeValue : attributeValuesRO)
			{
				attributeValue.addAttributeValueListener(attributeValueListener);
			}
		}

		public List<IAttributeValue> getAttributeValues()
		{
			return attributeValuesRO;
		}

		public IAttributeValue getAttributeValueOrNull(final int attributeId)
		{
			return attributeId2attributeValueRO.get(attributeId);
		}

		public boolean hasAttribute(final int attributeId)
		{
			return attributeId2attributeRO.containsKey(attributeId);
		}

		public Collection<I_M_Attribute> getAttributes()
		{
			return attributeId2attributeRO.values();
		}

		public I_M_Attribute getAttributeOrNull(int attributeId)
		{
			final I_M_Attribute attribute = attributeId2attributeRO.get(attributeId);
			return attribute;
		}

		public boolean hasAttributes()
		{
			return !attributeId2attributeRO.isEmpty();
		}

		public final I_M_Attribute getAttributeByValueKeyOrNull(final String attributeValueKey)
		{
			for (final I_M_Attribute attribute : attributeId2attributeRO.values())
			{
				if (attributeValueKey.equals(attribute.getValue()))
				{
					return attribute;
				}
			}

			return null;
		}

		public boolean isNull()
		{
			return this == NULL;
		}

	}
}
