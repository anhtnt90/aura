/*
 * Copyright (C) 2013 salesforce.com, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.auraframework.impl.root.component;

import java.util.Collections;

import org.auraframework.Aura;
import org.auraframework.def.AttributeDefRef;
import org.auraframework.def.ComponentDef;
import org.auraframework.def.DefDescriptor;
import org.auraframework.impl.AuraImplTestCase;
import org.auraframework.impl.expression.PropertyReferenceImpl;
import org.auraframework.instance.AttributeSet;
import org.auraframework.instance.Component;
import org.auraframework.system.AuraContext;
import org.auraframework.system.Location;
import org.auraframework.throwable.quickfix.AttributeNotFoundException;
import org.auraframework.throwable.quickfix.QuickFixException;

public class ComponentImplTest extends AuraImplTestCase {

    protected final static String baseComponentTag = "<aura:component %s>%s</aura:component>";

    public ComponentImplTest(String name) {
        super(name);
    }

    public void testComponentAttributeMap() throws Exception {
        Component cmp = vendor.makeComponent("test:child1", "meh");
        AttributeDefRef adr = vendor.makeAttributeDefRef("attr", "some value", new Location("meh", 0));
        cmp.getAttributes().set(Collections.singleton(adr));

        assertEquals(1, cmp.getAttributes().size());

        assertEquals("some value", cmp.getAttributes().getExpression("attr"));

        try {
            AttributeDefRef adr2 = vendor.makeAttributeDefRef("badAttr", "blubber", new Location("meh", 0));
            cmp.getAttributes().set(Collections.singleton(adr2));
            fail("Should have thrown AuraException(Attribute not Defined)");
        } catch (AttributeNotFoundException expected) {
        }
    }

    public void testSerialize() throws Exception {
        Component testComponent = vendor.makeComponent("test:parent", "fuh");
        testComponent.getClass();
        serializeAndGoldFile(testComponent);
    }
    
    public void testReinitializeModel() throws Exception {
        Component cmp = vendor.makeComponent("test:child1", "meh");

        PropertyReferenceImpl propRef = new PropertyReferenceImpl("value", null);
		assertEquals(null, cmp.getModel().getValue(propRef));
        
        setAttribute(cmp, "attr", "some value");

		assertEquals(null, cmp.getModel().getValue(propRef));
		
		cmp.reinitializeModel();
        assertEquals("some value", cmp.getModel().getValue(propRef));
        
        setAttribute(cmp, "attr", "some other value");
		cmp.reinitializeModel();
        assertEquals("some other value", cmp.getModel().getValue(propRef));
    }

	private void setAttribute(Component cmp, String name, String value) throws QuickFixException {
		AttributeDefRef adr = vendor.makeAttributeDefRef(name, value, new Location("meh", 0));
        AttributeSet attributes = cmp.getAttributes();
		attributes.set(Collections.singleton(adr));
	}

    public void testServerDependenceComponentSuperWithProviderOnly() throws Exception {
        Aura.getContextService().startContext(AuraContext.Mode.PROD, AuraContext.Format.HTML, AuraContext.Authentication.AUTHENTICATED);
        DefDescriptor<ComponentDef> cmp = definitionService.getDefDescriptor(
            "ui:pagerNextPrevious", ComponentDef.class);
        assertFalse("Component with super that has provider only should not have server dependencies",
            cmp.getDef().hasLocalDependencies());
    }

    public void testServerDependenceWithNestedServerDependentChild() throws Exception {
        assertTrue("Component with nested server dependent component should also be server dependent",
            hasDependencies(true, "<%s />"));
    }

    public void testServerDependenceWithNestedClientCreatableChild() throws Exception {
        assertFalse("Component with nested client creatable component should not have server dependencies",
            hasDependencies(false, "<%s />"));
    }

    public void testServerDependenceWithServerDependentChildInIf() throws Exception {
        assertTrue("Component with nested server dependent component in <aura:if> should have server dependencies",
            hasDependencies(true, "<aura:if isTrue='true'><%s /></aura:if>"));
    }

    public void testServerDependenceWithServerDependentChildInElseOfIf() throws Exception {
        assertTrue("Component with nested server dependent component in <aura:if> should have server dependencies",
            hasDependencies(true, "<aura:if isTrue='false'><ui:button label='false' /><aura:set attribute='else'><%s /></aura:set></aura:if>"));
    }

    public void testServerDependenceWithClientChildInIf() throws Exception {
        assertFalse("Component with nested client creatable component in <aura:if> should not have server dependencies",
            hasDependencies(false, "<aura:if isTrue='true'><%s /></aura:if>"));
    }

    public void testServerDependenceWithClientChildInIfElse() throws Exception {
        assertFalse("Component with nested server dependent component in <aura:if> should have server dependencies",
            hasDependencies(false, "<aura:if isTrue='false'><ui:button label='false' /><aura:set attribute='else'><%s /></aura:set></aura:if>"));
    }

    public void testServerDependenceWithServerDependentChildInRenderIf() throws Exception {
        assertTrue("Component with nested server dependent component in <aura:renderIf> should have server dependencies",
            hasDependencies(true, "<aura:renderIf isTrue='false'><%s /></aura:renderIf>"));
    }

    public void testServerDependenceWithClientChildInRenderIf() throws Exception {
        assertFalse("Component with nested client creatable component in <aura:renderIf> should not have server dependencies",
            hasDependencies(false, "<aura:renderIf isTrue='true'><%s /></aura:renderIf>"));
    }

    public void testServerDependenceWithServerDependentChildInIteration() throws Exception {
        assertTrue("Component with nested server dependent component in <aura:renderIf> should have server dependencies",
            hasDependencies(true, "<aura:attribute name='items' type='List'/><aura:iteration items='{!v.items}'><%s /></aura:iteration>"));
    }

    public void testServerDependenceWithClientChildInIteration() throws Exception {
        assertFalse("Component with nested client creatable component in <aura:renderIf> should not have server dependencies",
            hasDependencies(false, "<aura:attribute name='items' type='List'/><aura:iteration items='{!v.items}'><%s /></aura:iteration>"));
    }

    public void testServerDependenceRecursiveComponent() throws Exception {
        Aura.getContextService().startContext(AuraContext.Mode.PROD, AuraContext.Format.HTML, AuraContext.Authentication.AUTHENTICATED);
        DefDescriptor<ComponentDef> cmp = definitionService.getDefDescriptor(
                "ui:treeNode", ComponentDef.class);
        assertFalse("Recursive components ie ui:treeNode should not cause stack overflow.",
                cmp.getDef().hasLocalDependencies());
    }

    private boolean hasDependencies(boolean hasModel, String componentBody) throws Exception {
        String componentAttributes = hasModel ? "model='java://test.model.TestReinitializeModel'" : "";
        DefDescriptor<ComponentDef> grandCmp = addSourceAutoCleanup(ComponentDef.class,
                String.format(baseComponentTag, componentAttributes, ""));
        DefDescriptor<ComponentDef> childCmp = addSourceAutoCleanup(ComponentDef.class,
                String.format(baseComponentTag, "", String.format(componentBody, grandCmp.getDescriptorName())));
        DefDescriptor<ComponentDef> cmp = addSourceAutoCleanup(ComponentDef.class,
                String.format(baseComponentTag, "", String.format("<%s />", childCmp.getDescriptorName())));
        return cmp.getDef().hasLocalDependencies();
    }

}
