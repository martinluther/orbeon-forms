/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.control.controls

import java.{util ⇒ ju}

import org.dom4j.Element
import org.orbeon.oxf.util.ScalaUtils._
import org.orbeon.oxf.util.XPathCache
import org.orbeon.oxf.xforms.XFormsConstants._
import org.orbeon.oxf.xforms.analysis.ControlAnalysisFactory.{CaseControl, SwitchControl}
import org.orbeon.oxf.xforms.control.{ControlLocalSupport, XFormsControl, XFormsSingleNodeContainerControl}
import org.orbeon.oxf.xforms.event.Dispatch
import org.orbeon.oxf.xforms.event.events.{XFormsDeselectEvent, XFormsSelectEvent}
import org.orbeon.oxf.xforms.model.DataModel
import org.orbeon.oxf.xforms.state.ControlState
import org.orbeon.oxf.xforms.xbl.XBLContainer
import org.orbeon.oxf.xforms.{BindingContext, XFormsContainingDocument, XFormsUtils}
import org.orbeon.oxf.xml.XMLReceiverHelper
import org.orbeon.saxon.om.Item
import org.xml.sax.helpers.AttributesImpl

/**
 * Represents an xf:switch container control.
 *
 * NOTE: This keep the "currently selected flag" for all children xf:case.
 */
class XFormsSwitchControl(container: XBLContainer, parent: XFormsControl, element: Element, effectiveId: String)
        extends XFormsSingleNodeContainerControl(container, parent, element, effectiveId) {

    override type Control <: SwitchControl

    // Initial local state
    setLocal(new XFormsSwitchControlLocal)

    private var _caserefBinding: Option[Item] = None

    // NOTE: state deserialized -> state previously serialized -> control was relevant -> onCreate() called
    override def onCreate(restoreState: Boolean, state: Option[ControlState]): Unit = {
        super.onCreate(restoreState, state)

        _caserefBinding = evaluateCaseRefBinding

        // Ensure that the initial state is set, either from default value, or for state deserialization.
        state match {
            case Some(state) ⇒
                setLocal(new XFormsSwitchControlLocal(state.keyValues("case-id")))
            case None if restoreState ⇒
                // This can happen with xxf:dynamic, which does not guarantee the stability of ids, therefore state for a
                // particular control might not be found.
                setLocal(new XFormsSwitchControlLocal(findInitialSelectedCaseId))
            case None ⇒
                val local = getLocalForUpdate.asInstanceOf[XFormsSwitchControlLocal]
                local.selectedCaseControlId = findInitialSelectedCaseId
                // TODO: deferred event dispatch for xforms-select/deselect???
        }
    }

    override def onBindingUpdate(oldBinding: BindingContext, newBinding: BindingContext): Unit = {
        super.onBindingUpdate(oldBinding, newBinding)

        _caserefBinding = evaluateCaseRefBinding

        if (staticControl.caseref.isDefined) {
            val newCaseId = caseIdFromCaseRefBinding getOrElse firstCaseId
            val local     = getLocalForUpdate.asInstanceOf[XFormsSwitchControlLocal]

//            val previouslySelectedCaseControl = selectedCase.get
            local.selectedCaseControlId = newCaseId
        }

        // TODO: deferred event dispatch for xforms-select/deselect
    }

    private def evaluateCaseRefBinding: Option[Item] =
        staticControl.caseref flatMap { caseref ⇒

            val caserefItem =
                Option(
                    XPathCache.evaluateSingleKeepItems(
                        contextItems       = bindingContext.childContext,
                        contextPosition    = bindingContext.position,
                        xpathString        = caseref,
                        namespaceMapping   = staticControl.namespaceMapping,
                        variableToValueMap = bindingContext.getInScopeVariables,
                        functionLibrary    = XFormsContainingDocument.getFunctionLibrary,
                        functionContext    = newFunctionContext,
                        baseURI            = null,
                        locationData       = staticControl.locationData,
                        reporter           = containingDocument.getRequestStats.getReporter
                    )
                )

            caserefItem collect {
                case item if DataModel.isAllowedValueBoundItem(item) ⇒ item
            }

            // TODO: deferred event dispatch for xforms-binding-error EXCEPT upon restoring state???
        }

    // "If the caseref attribute is specified, then it takes precedence over the selected attributes of the case
    // elements"
    private def findInitialSelectedCaseId =
        if (staticControl.caseref.isDefined)
            caseIdFromCaseRefBinding getOrElse firstCaseId
        else
            caseIdFromSelected getOrElse firstCaseId

    private def caseIdFromCaseRefBinding: Option[String] =
        _caserefBinding flatMap { item ⇒
            Some(item.getStringValue) flatMap caseForValue map (_.staticId)
        }

    // The value associated with a given xf:case can come from:
    //
    // - a literal string specified with @value (this is an optimization)
    // - a dynamic expression specified with @value
    // - the case id
    //
    // NOTE: A nested xf:value element should also be supported for consistency with xf:item.
    //
    private def caseValue(c: CaseControl) = {

        def fromLiteral(c: CaseControl) =
            c.valueLiteral

        // FIXME: The expression is evaluated in the context of xf:switch, when in fact it should be evaluated in the
        // context of the xf:case, including variables and FunctionContext.
        def fromExpression(c: CaseControl) = c.valueExpression flatMap { expr ⇒
            Option(
                XPathCache.evaluateAsString(
                    contextItems       = bindingContext.childContext,
                    contextPosition    = bindingContext.position,
                    xpathString        = expr,
                    namespaceMapping   = c.namespaceMapping,
                    variableToValueMap = bindingContext.getInScopeVariables,
                    functionLibrary    = XFormsContainingDocument.getFunctionLibrary,
                    functionContext    = newFunctionContext,
                    baseURI            = null,
                    locationData       = c.locationData,
                    reporter           = containingDocument.getRequestStats.getReporter
                )
            )
        }

        fromLiteral(c) orElse fromExpression(c) getOrElse c.staticId
    }

    private def caseForValue(value: String) =
        staticControl.caseControls find (caseValue(_) == value)

    private def caseIdFromSelected: Option[String] = {

        // FIXME: The AVT is evaluated in the context of xf:switch, when in fact it should be evaluated in the  context
        // of the xf:case, including namespaces, variables and FunctionContext.
        def isSelected(c: CaseControl) =
            c.selected exists evaluateBooleanAvt

        staticControl.caseControls find isSelected map (_.staticId)
    }

    // NOTE: This assumes there is at least one child case element.
    private def firstCaseId =
        staticControl.caseControls.head.staticId

    // Filter because XXFormsVariableControl can also be a child
    def getChildrenCases =
        children collect { case c: XFormsCaseControl ⇒ c }

    // Set the currently selected case.
    def setSelectedCase(caseControlToSelect: XFormsCaseControl): Unit = {

        require(caseControlToSelect.parent eq this, s"xf:case '${caseControlToSelect.effectiveId}' is not child of current xf:switch")

        val previouslySelectedCaseControl = selectedCase.get

        if (staticControl.caseref.isDefined) {
            // "by performing a setvalue action if the caseref attribute is specified and indicates a node. If the
            // node is readonly or if the toggle action does not indicate a case in the switch, then no value change
            // occurs and therefore no change of the selected case occurs"
            _caserefBinding flatMap (item ⇒ DataModel.isWritableItem(item).left.toOption) foreach { writableNode ⇒

                val newValue = caseValue(caseControlToSelect.staticControl)

                DataModel.setValueIfChanged(
                    nodeInfo  = writableNode,
                    newValue  = newValue,
                    onSuccess = oldValue ⇒ DataModel.logAndNotifyValueChange(
                        containingDocument = containingDocument,
                        source             = "toggle",
                        nodeInfo           = writableNode,
                        oldValue           = oldValue,
                        newValue           = newValue,
                        isCalculate        = false
                    )
                )
            }
        } else if (previouslySelectedCaseControl.getId != caseControlToSelect.getId) {

            containingDocument.requireRefresh()

            val localForUpdate = getLocalForUpdate.asInstanceOf[XFormsSwitchControlLocal]
            localForUpdate.selectedCaseControlId = caseControlToSelect.getId

            // "This action adjusts all selected attributes on the affected cases to reflect the new state, and then
            // performs the following:"

            // "1. Dispatching an xforms-deselect event to the currently selected case."
            Dispatch.dispatchEvent(new XFormsDeselectEvent(previouslySelectedCaseControl))

            if (isXForms11Switch) {
                // Partial refresh on the case that is being deselected
                // Do this after xforms-deselect is dispatched
                containingDocument.getControls.doPartialRefresh(previouslySelectedCaseControl)

                // Partial refresh on the case that is being selected
                // Do this before xforms-select is dispatched
                containingDocument.getControls.doPartialRefresh(caseControlToSelect)
            }

            // "2. Dispatching an xforms-select event to the case to be selected."
            Dispatch.dispatchEvent(new XFormsSelectEvent(caseControlToSelect))
        }
    }

    // Get the effective id of the currently selected case.
    def getSelectedCaseEffectiveId: String =
        if (isRelevant) {
            val local = getCurrentLocal.asInstanceOf[XFormsSwitchControlLocal]
            require(local.selectedCaseControlId ne null, s"Selected case was not set for xf:switch: $effectiveId")
            XFormsUtils.getRelatedEffectiveId(getEffectiveId, local.selectedCaseControlId)
        } else
            null

    def selectedCase =
        isRelevant option containingDocument.getControlByEffectiveId(getSelectedCaseEffectiveId).asInstanceOf[XFormsCaseControl]

    override def getBackCopy: AnyRef = {
        var cloned: XFormsSwitchControl = null

        // We want the new one to point to the children of the cloned nodes, not the children

        // Get initial index as we copy "back" to an initial state
        val initialLocal = getInitialLocal.asInstanceOf[XFormsSwitchControlLocal]

        // Clone this and children
        cloned = super.getBackCopy.asInstanceOf[XFormsSwitchControl]

        // Update clone's selected case control to point to one of the cloned children
        val clonedLocal = cloned.getInitialLocal.asInstanceOf[XFormsSwitchControlLocal]

        // NOTE: we don't call getLocalForUpdate() because we know that XFormsSwitchControlLocal is safe to write
        // to (super.getBackCopy() ensures that we have a new copy)
        clonedLocal.selectedCaseControlId = initialLocal.selectedCaseControlId

        cloned
    }

    // Serialize case id
    override def serializeLocal =
        ju.Collections.singletonMap("case-id", XFormsUtils.getStaticIdFromId(getSelectedCaseEffectiveId))

    override def focusableControls =
        if (isRelevant)
            selectedCase.iterator flatMap (_.focusableControls)
        else
            Iterator.empty

    override def equalsExternal(other: XFormsControl): Boolean = {
        if (! other.isInstanceOf[XFormsSwitchControl])
            return false

        // NOTE: don't give up on "this == other" because there can be a difference just in XFormsControlLocal

        val otherSwitchControl = other.asInstanceOf[XFormsSwitchControl]

        // Check whether selected case has changed
        if (getSelectedCaseEffectiveId != getOtherSelectedCaseEffectiveId(otherSwitchControl))
            return false

        super.equalsExternal(other)
    }

    override def outputAjaxDiff(ch: XMLReceiverHelper, other: XFormsControl, attributesImpl: AttributesImpl, isNewlyVisibleSubtree: Boolean): Unit = {

        // Output regular diff
        super.outputAjaxDiff(ch, other, attributesImpl, isNewlyVisibleSubtree)

        val otherSwitchControl = other.asInstanceOf[XFormsSwitchControl]
        if (isRelevant && getSelectedCaseEffectiveId != getOtherSelectedCaseEffectiveId(otherSwitchControl)) {

            // Output newly selected case id
            val selectedCaseEffectiveId = getSelectedCaseEffectiveId ensuring (_ ne null)

            ch.element("xxf", XXFORMS_NAMESPACE_URI, "div", Array(
                "id", XFormsUtils.namespaceId(containingDocument, selectedCaseEffectiveId),
                "visibility", "visible")
            )

            if ((otherSwitchControl ne null) && otherSwitchControl.isRelevant) {
                // Used to be relevant, simply output deselected case ids
                val previousSelectedCaseId = getOtherSelectedCaseEffectiveId(otherSwitchControl) ensuring (_ ne null)

                ch.element("xxf", XXFORMS_NAMESPACE_URI, "div", Array(
                    "id", XFormsUtils.namespaceId(containingDocument, previousSelectedCaseId),
                    "visibility", "hidden")
                )
            } else {
                // Control was not relevant, send all deselected to be sure
                // TODO: This should not be needed because the repeat template should have a reasonable default.
                getChildrenCases filter (_.getEffectiveId != selectedCaseEffectiveId) foreach { caseControl ⇒
                    ch.element("xxf", XXFORMS_NAMESPACE_URI, "div", Array(
                        "id", XFormsUtils.namespaceId(containingDocument, caseControl.getEffectiveId ensuring (_ ne null)),
                        "visibility", "hidden")
                    )
                }
            }
        }
    }

    private def getOtherSelectedCaseEffectiveId(switchControl1: XFormsSwitchControl): String =
        if ((switchControl1 ne null) && switchControl1.isRelevant) {
            val selectedCaseId = switchControl1.getInitialLocal.asInstanceOf[XFormsSwitchControlLocal].selectedCaseControlId
            assert(selectedCaseId ne null)
            XFormsUtils.getRelatedEffectiveId(switchControl1.getEffectiveId, selectedCaseId)
        } else
            null

    def isXForms11Switch: Boolean = {
        val localXForms11Switch = element.attributeValue(XXFORMS_XFORMS11_SWITCH_QNAME)
        if (localXForms11Switch ne null)
            localXForms11Switch.toBoolean
        else
            containingDocument.isXForms11Switch
    }

    override def valueType = null
}

private class XFormsSwitchControlLocal(var selectedCaseControlId: String = null)
    extends ControlLocalSupport.XFormsControlLocal
