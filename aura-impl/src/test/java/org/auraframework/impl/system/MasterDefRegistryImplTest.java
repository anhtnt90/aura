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
package org.auraframework.impl.system;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.auraframework.Aura;
import org.auraframework.adapter.ConfigAdapter;
import org.auraframework.adapter.RegistryAdapter;
import org.auraframework.cache.Cache;
import org.auraframework.def.*;
import org.auraframework.def.DefDescriptor.DefType;
import org.auraframework.impl.AuraImpl;
import org.auraframework.impl.AuraImplTestCase;
import org.auraframework.impl.source.StringSourceLoader;
import org.auraframework.service.BuilderService;
import org.auraframework.service.ContextService;
import org.auraframework.system.*;
import org.auraframework.system.AuraContext.Authentication;
import org.auraframework.system.AuraContext.Format;
import org.auraframework.system.AuraContext.Mode;
import org.auraframework.test.AuraTestingUtil;
import org.auraframework.test.annotation.ThreadHostileTest;
import org.auraframework.test.annotation.UnAdaptableTest;
import org.auraframework.test.util.AuraPrivateAccessor;
import org.auraframework.throwable.NoAccessException;
import org.auraframework.throwable.quickfix.*;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.internal.util.MockUtil;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;

/**
 * @see org.auraframework.impl.registry.RootDefFactoryTest
 */
@ThreadHostileTest("Don't you go clearing my caches.")
public class MasterDefRegistryImplTest extends AuraImplTestCase {
    @Mock Definition globalDef;
    @Mock DefinitionAccess defAccess;
    @Mock DefDescriptor<ComponentDef> referencingDesc;
    @Mock Cache<String, String> mockAccessCheckCache;
    
    public MasterDefRegistryImplTest(String name) {
        super(name);
    }

    private MasterDefRegistryImpl getDefRegistry(boolean asMocks) {
        Collection<RegistryAdapter> providers = AuraImpl.getRegistryAdapters();
        List<DefRegistry<?>> mdrregs = Lists.newArrayList();

        AuraContext context = Aura.getContextService().getCurrentContext();
        for (RegistryAdapter provider : providers) {
            DefRegistry<?>[] registries = provider.getRegistries(context.getMode(), context.getAccess(), null);
            if (registries != null) {
                for (DefRegistry<?> reg : registries) {
                    Set<String> ns = reg.getNamespaces();

                    if (ns != null && (ns.contains("aura") || ns.contains("*"))) {
                        mdrregs.add(asMocks ? Mockito.spy(reg) : reg);
                    }
                }
            }
        }
        MasterDefRegistryImpl registry = new MasterDefRegistryImpl(mdrregs.toArray(new DefRegistry<?>[mdrregs.size()]));
        return asMocks ? Mockito.spy(registry) : registry;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void spyOnDefs(final MasterDefRegistryImpl registry) throws QuickFixException {
        final MockUtil mockUtil = new MockUtil();
        for (DefRegistry<?> subReg : registry.getAllRegistries()) {
            Mockito.doAnswer(new Answer<Definition>() {
                @Override
                public Definition answer(InvocationOnMock invocation) throws Throwable {
                    Definition ret = (Definition) invocation.callRealMethod();
                    if (mockUtil.isMock(ret)) {
                        return ret;
                    } else {
                        ret = Mockito.spy(ret);
                        registry.addLocalDef(ret);
                        return ret;
                    }
                }
            }).when(subReg).getDef(Mockito.<DefDescriptor> any());
        }
    }

    /**
     * Verify some of the assertions (copied here) made by compileDef (excluding #2 & #5).
     * <ol>
     * <li>Each definition has 'validateDefinition()' called on it exactly once.</li>
     * <li>No definition is marked as valid until all definitions in the dependency set have been validated</li>
     * <li>Each definition has 'validateReferences()' called on it exactly once, after the definitions have been put in
     * local cache</li>
     * <li>All definitions are marked valid by the DefRegistry after the validation is complete</li>
     * <li>No definition should be available to other threads until it is marked valid</li>
     * <ol>
     */
    private void assertCompiledDef(Definition def) throws QuickFixException {
        Mockito.verify(def, Mockito.times(1)).validateDefinition();
        Mockito.verify(def, Mockito.times(1)).validateReferences();
        Mockito.verify(def, Mockito.times(1)).markValid();
        assertEquals("definition not valid: " + def, true, def.isValid());
    }

    private void assertIdenticalDependencies(DefDescriptor<?> desc1, DefDescriptor<?> desc2) throws Exception {
        MasterDefRegistryImpl registry = getDefRegistry(false);
        Set<DefDescriptor<?>> deps1 = registry.getDependencies(registry.getUid(null, desc1));
        Set<DefDescriptor<?>> deps2 = registry.getDependencies(registry.getUid(null, desc2));
        assertNotNull(deps1);
        assertNotNull(deps2);
        assertEquals("Descriptors should have the same number of dependencies", deps1.size(), deps2.size());

        // Loop through and check individual dependencies. Order doesn't matter.
        for (DefDescriptor<?> dep : deps1) {
            assertTrue("Descriptors do not have identical dependencies",
                    checkDependenciesContains(deps2, dep.getQualifiedName()));
        }
    }

    private boolean checkDependenciesContains(Set<DefDescriptor<?>> deps, String depSearch) {
        for (DefDescriptor<?> dep : deps) {
            if (dep.getQualifiedName().equals(depSearch)) {
                return true;
            }
        }
        return false;
    }

    private void updateStringSource(DefDescriptor<?> desc, String content) {
        Source<?> src = StringSourceLoader.getInstance().getSource(desc);
        src.addOrUpdate(content);
    }

    public void testFindRegex() throws Exception {
        String namespace = "testFindRegex" + getAuraTestingUtil().getNonce();
        DefDescriptor<ApplicationDef> houseboat = addSourceAutoCleanup(ApplicationDef.class,
                String.format(baseApplicationTag, "", ""), String.format("%s:houseboat", namespace));
        addSourceAutoCleanup(ApplicationDef.class, String.format(baseApplicationTag, "", ""),
                String.format("%s:houseparty", namespace));
        addSourceAutoCleanup(ApplicationDef.class, String.format(baseApplicationTag, "", ""),
                String.format("%s:pantsparty", namespace));

        MasterDefRegistryImpl masterDefReg = getDefRegistry(false);

        assertTrue("find() not finding all sources",
                masterDefReg.find(new DescriptorFilter(String.format("markup://%s:*", namespace))).size() == 3);
        assertEquals("find() fails with wildcard as prefix", 1,
                masterDefReg.find(new DescriptorFilter("*://" + houseboat.getDescriptorName())).size());
        assertEquals("find() fails with wildcard as namespace", 1,
                masterDefReg.find(new DescriptorFilter("markup://*:" + houseboat.getName())).size());
        assertEquals("find() fails with wildcard as name", 1,
                masterDefReg.find(new DescriptorFilter(houseboat.getQualifiedName())).size());
        assertEquals("find() fails with wildcard at end of name", 2,
                masterDefReg.find(new DescriptorFilter(String.format("markup://%s:house*", namespace))).size());
        assertEquals("find() fails with wildcard at beginning of name", 2,
                masterDefReg.find(new DescriptorFilter(String.format("markup://%s:*party*", namespace))).size());

        assertEquals("find() should not find nonexistent name", 0,
                masterDefReg.find(new DescriptorFilter(String.format("markup://%s:househunters", namespace))).size());
        assertEquals("find() should not find nonexistent name ending with wildcard", 0,
                masterDefReg.find(new DescriptorFilter(String.format("markup://%s:househunters*", namespace))).size());
        assertEquals("find() should not find nonexistent name with preceeding wildcard", 0,
                masterDefReg.find(new DescriptorFilter(String.format("markup://%s:*notherecaptain", namespace))).size());
    }

    private static class AddableDef<T extends Definition> {
        private Class<T> defClass;
        private String format;
        private String content;

        public AddableDef(Class<T> defClass, String format, String content) {
            this.defClass = defClass;
            this.format = format;
            this.content = content;
        }

        public Class<T> getDefClass() { return this.defClass; }
        public String getFQN(String namespace, String name) {
            return String.format(this.format, namespace, name);
        }
        public String getContent() { return content; }
    }

    private static AddableDef<?> addable [] = new AddableDef [] {
        // Ignoring top level bundle defs.
        // APPLICATION(ApplicationDef.class, Format.XML, DefDescriptor.MARKUP_PREFIX, ":"),
        // COMPONENT(ComponentDef.class, Format.XML, DefDescriptor.MARKUP_PREFIX, ":"),
        // EVENT(EventDef.class, Format.XML, DefDescriptor.MARKUP_PREFIX, ":"),
        // INTERFACE(InterfaceDef.class, Format.XML, DefDescriptor.MARKUP_PREFIX, ":"),
        // LAYOUTS(LayoutsDef.class, Format.XML, DefDescriptor.MARKUP_PREFIX, ":"),
        // NAMESPACE(NamespaceDef.class, Format.XML, DefDescriptor.MARKUP_PREFIX, ""),
        new AddableDef<ControllerDef>(ControllerDef.class, "js://%s.%s",
                "({method: function(cmp) {}})"),
        new AddableDef<HelperDef>(HelperDef.class, "js://%s.%s",
                "({method: function(cmp) {}})"),
        //new AddableDef<ProviderDef>(ProviderDef.class, "js://%s.%s",
        //        "({provide: function(cmp) {}})"),
        new AddableDef<RendererDef>(RendererDef.class, "js://%s.%s",
                "({render: function(cmp) {}})"),
        new AddableDef<StyleDef>(StyleDef.class, "css://%s.%s",
                ".THIS {display:block;}"),
        // Ignoring TESTSUITE(TestSuiteDef.class, Format.JS, DefDescriptor.JAVASCRIPT_PREFIX, "."),
        // Ignoring THEME(ThemeDef.class, Format.XML, DefDescriptor.MARKUP_PREFIX, ":");
    };

    private MasterDefRegistry resetDefRegistry() {
        ContextService contextService = Aura.getContextService();
        if (contextService.isEstablished()) {
            contextService.endContext();
        }
        contextService.startContext(Mode.UTEST, Format.JSON, Authentication.AUTHENTICATED);
        return contextService.getCurrentContext().getDefRegistry();
    }

    private <T extends Definition> void checkAddRemove(DefDescriptor<?> tld, String suid,
            AddableDef<T> toAdd) throws QuickFixException {
        DefDescriptor<T> dd;
        String uid, ouid;
        Set<DefDescriptor<?>> deps;
        AuraTestingUtil util = getAuraTestingUtil();
        MasterDefRegistry mdr;

        dd = DefDescriptorImpl.getInstance(toAdd.getFQN(tld.getNamespace(), tld.getName()),
                toAdd.getDefClass());
        util.addSourceAutoCleanup(dd, toAdd.getContent());
        mdr = resetDefRegistry();
        uid = mdr.getUid(null, tld);
        assertFalse("UID should change on add for "+dd.getDefType()+"@"+dd, suid.equals(uid));
        deps = mdr.getDependencies(uid);
        assertTrue("dependencies should contain the newly created "+dd.getDefType()+"@"+dd,
                deps.contains(dd));
        ouid = uid;
        util.removeSource(dd);
        mdr = resetDefRegistry();
        uid = mdr.getUid(null, tld);
        assertNotSame("UID should change on removal for "+dd.getDefType()+"@"+dd, ouid, uid);
        deps = mdr.getDependencies(uid);
        assertFalse("dependencies should not contain the deleted "+dd, deps.contains(dd));
    }

    private <T extends Definition> void checkOneTLD(String fqn, Class<T> clazz, String content)
            throws QuickFixException {
        AuraTestingUtil util = getAuraTestingUtil();
        String uid;

        DefDescriptor<T> tld = DefDescriptorImpl.getInstance(fqn, clazz);
        util.addSourceAutoCleanup(tld, content);
        MasterDefRegistry mdr = resetDefRegistry();
        // prime the cache.
        uid = mdr.getUid(null, tld);
        assertNotNull(tld+" did not give us a UID", uid);
        for (AddableDef<?> adding : addable) {
            checkAddRemove(tld, uid, adding);
        }
        util.removeSource(tld);
    }

    public void testComponentChChChChanges() throws Exception {
        checkOneTLD("markup://chchch:changes"+getAuraTestingUtil().getNonce(),
                ComponentDef.class, "<aura:component></aura:component>");
    }

    public void testApplicationChChChChanges() throws Exception {
        checkOneTLD("markup://chchch:changes"+getAuraTestingUtil().getNonce(),
                ApplicationDef.class, "<aura:application></aura:application>");
    }

    public void testStringCache() throws Exception {
        String namespace = "testStringCache" + getAuraTestingUtil().getNonce();
        DefDescriptor<ApplicationDef> houseboat = addSourceAutoCleanup(ApplicationDef.class,
                String.format(baseApplicationTag, "", ""), String.format("%s:houseboat", namespace));
        MasterDefRegistryImpl masterDefReg = getDefRegistry(false);
        String uid = masterDefReg.getUid(null, houseboat);
        assertNull("Found string in new MDR", masterDefReg.getCachedString(uid, houseboat, "test1"));
        masterDefReg.putCachedString(uid, houseboat, "test1", "value");
        assertEquals("value", masterDefReg.getCachedString(uid, houseboat, "test1"));
    }

    public void testNonPrivilegedStringCache() throws Exception {
        String namespace = "testNonPrivilegedStringCache" + getAuraTestingUtil().getNonce();

        ConfigAdapter configAdapter = Aura.getConfigAdapter();
        assertFalse(namespace + "  should not have been isPriveleged", configAdapter.isPrivilegedNamespace(namespace));
        DefDescriptor<ApplicationDef> houseboat = getAuraTestingUtil().addSourceAutoCleanup(ApplicationDef.class,
                String.format(baseApplicationTag, "", ""), String.format("%s:houseboat", namespace), false);
        MasterDefRegistryImpl masterDefReg = getDefRegistry(false);
        String uid = masterDefReg.getUid(null, houseboat);
        assertNull("Found string in new MDR", masterDefReg.getCachedString(uid, houseboat, "test1"));
        masterDefReg.putCachedString(uid, houseboat, "test1", "value");
        assertNull("Found string in new MDR", masterDefReg.getCachedString(uid, houseboat, "test1"));
    }

    public void testGetUidClientOutOfSync() throws Exception {
        String namespace = "testStringCache" + getAuraTestingUtil().getNonce();
        String namePrefix = String.format("%s:houseboat", namespace);
        DefDescriptor<ApplicationDef> houseboat = addSourceAutoCleanup(ApplicationDef.class,
                String.format(baseApplicationTag, "", ""), namePrefix);
        MasterDefRegistryImpl masterDefReg = getDefRegistry(false);
        String uid = masterDefReg.getUid(null, houseboat);
        assertNotNull(uid);
        // Check unchanged app gets same UID value
        assertEquals(uid, masterDefReg.getUid(uid, houseboat));

        //
        // When given an incorrect UID, masterDefReg simply returns the correct one.
        String newUid = masterDefReg.getUid(uid + " or not", houseboat);
        assertEquals(uid, newUid);
    }

    /**
     * Verify getting the UID of a dependency doesn't affect the original UID.
     */
    public void testUidDependencies() throws Exception {
        DefDescriptor<ComponentDef> child = addSourceAutoCleanup(ComponentDef.class,
                "<aura:component></aura:component>", "testUidDependenciesChild");
        DefDescriptor<ApplicationDef> parent = addSourceAutoCleanup(ApplicationDef.class,
                "<aura:application><" + child.getDescriptorName() + "/></aura:application>",
                "testUidDependenciesParent");

        MasterDefRegistryImpl masterDefReg1 = getDefRegistry(false);
        String parentUid1 = masterDefReg1.getUid(null, parent);

        MasterDefRegistryImpl masterDefReg2 = getDefRegistry(false);
        masterDefReg2.getUid(null, child);
        String parentUid2 = masterDefReg2.getUid(null, parent);

        assertTrue("UIDs do not match after getting a dependencies UID", parentUid1.equals(parentUid2));
    }

    /**
     * Verify UID values and dependencies against a gold file.
     * 
     * This does a recursive set of dependencies checks to build a gold file with the resulting descriptors and UIDs to
     * ensure that we get both a valid set and can tell what changed (and thus verify that it should have changed).
     * 
     * The format of the file is:
     * <ul>
     * <li>Top level descriptor ':' global UID.
     * <li>
     * <li>dependency ':' own hash
     * <li>
     * <li>...</li>
     * </ul>
     */
    public void testUidValue() throws Exception {
        StringBuilder buffer = new StringBuilder();
        String cmpName = "ui:outputNumber";
        DefDescriptor<ComponentDef> desc = Aura.getDefinitionService()
                .getDefDescriptor(cmpName, ComponentDef.class);
        MasterDefRegistryImpl masterDefReg = getDefRegistry(false);
        String uid = masterDefReg.getUid(null, desc);
        assertNotNull("Could not retrieve UID for component " + cmpName, uid);
        Set<DefDescriptor<?>> dependencies = masterDefReg.getDependencies(uid);
        assertNotNull("Could not retrieve dependencies for component " + cmpName, dependencies);

        buffer.append(desc.toString());
        buffer.append(" : ");
        buffer.append(uid);
        buffer.append("\n");

        for (DefDescriptor<?> dep : dependencies) {
            buffer.append(dep);
            buffer.append(" : ");
            buffer.append(masterDefReg.getDef(dep).getOwnHash());
            buffer.append("\n");
        }
        goldFileText(buffer.toString());
    }

    public void testGetUidDescriptorNull() throws Exception {
        MasterDefRegistryImpl registry = getDefRegistry(false);
        assertNull(registry.getUid(null, null));
    }

    public void testGetUidDescriptorDoesntExist() throws Exception {
        MasterDefRegistryImpl registry = getDefRegistry(false);
        assertNull(registry.getUid(null, DefDescriptorImpl.getInstance("unknown:soldier", ComponentDef.class)));
    }

    public void testGetUidLocalDef() throws Exception {
        MasterDefRegistryImpl registry = getDefRegistry(false);
        ComponentDef def = Mockito.spy(registry.getDef(DefDescriptorImpl.getInstance("aura:component",
                ComponentDef.class)));
        registry.invalidate(null); // clear any cached results from the preceding getDef call
        registry = getDefRegistry(false);
        registry.addLocalDef(def);
        assertNotNull(registry.getUid(null, def.getDescriptor()));
    }

    public void testGetUidSameAcrossInstances() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        MasterDefRegistryImpl registry1 = getDefRegistry(false);
        String uid1 = registry1.getUid(null, cmpDesc);
        MasterDefRegistryImpl registry2 = getDefRegistry(false);
        registry2.invalidate(null);
        String uid2 = registry2.getUid(null, cmpDesc);
        assertEquals("Expected same UID for def from separate registry instances", uid1, uid2);
    }

    public void testGetUidUnique() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc1 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDesc2 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        MasterDefRegistryImpl registry = getDefRegistry(false);
        String uid1 = registry.getUid(null, cmpDesc1);
        String uid2 = registry.getUid(null, cmpDesc2);
        assertTrue("Components with same markup and dependencies should have different UIDs", !uid1.equals(uid2));
    }

    public void testGetUidCachedForChangedDefinition() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        MasterDefRegistryImpl registry = getDefRegistry(false);
        String uid = registry.getUid(null, cmpDesc);

        // UID cached for current registry
        registry.getSource(cmpDesc).addOrUpdate(
                "<aura:component><aura:attribute name='str' type='String'/></aura:component>");
        String uidNew = registry.getUid(null, cmpDesc);
        assertEquals("UID not cached", uid, uidNew);

        // UID not cached for new registry
        MasterDefRegistryImpl registryNext = getDefRegistry(false);
        String uidNext = registryNext.getUid(null, cmpDesc);
        assertFalse("UID not cached in new registry", uid.equals(uidNext));
    }

    public void testGetUidCachedForRemovedDefinition() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>", null);
        MasterDefRegistryImpl registry = getDefRegistry(false);
        String uid = registry.getUid(null, cmpDesc);

        // UID cached for current registry
        getAuraTestingUtil().removeSource(cmpDesc);
        String uidNew = registry.getUid(null, cmpDesc);
        assertEquals("UID not cached", uid, uidNew);

        // UID not cached for new registry
        MasterDefRegistryImpl registryNext = getDefRegistry(false);
        String uidNext = registryNext.getUid(null, cmpDesc);
        assertNull("UID cached in new registry", uidNext);
    }

    public void testGetUidForQuickFixException() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class,
                "<aura:component><unknown:component/></aura:component>", null);
        MasterDefRegistryImpl registry = getDefRegistry(true);
        try {
            registry.getUid(null, cmpDesc);
            fail("Expected DefinitionNotFoundException");
        } catch (DefinitionNotFoundException e) {
            checkExceptionStart(e, null, "No COMPONENT named markup://unknown:component found");
        }
        Mockito.verify(registry, Mockito.times(1)).compileDE(Mockito.eq(cmpDesc));

        // another request for getUid will not re-compile
        Mockito.reset(registry);
        try {
            registry.getUid(null, cmpDesc);
            fail("Expected DefinitionNotFoundException");
        } catch (DefinitionNotFoundException e) {
            checkExceptionStart(e, null, "No COMPONENT named markup://unknown:component found");
        }
        Mockito.verify(registry, Mockito.times(0)).compileDE(Mockito.eq(cmpDesc));
    }

    public void testGetUidForNonQuickFixException() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class,
                "<aura:component invalidAttribute=''/>", null);
        MasterDefRegistryImpl registry = getDefRegistry(true);
        try {
            registry.getUid(null, cmpDesc);
            fail("Expected InvalidDefinitionException");
        } catch (Throwable t) {
            checkExceptionFull(t, InvalidDefinitionException.class,
                    String.format("%s:1,38: Invalid attribute \"invalidAttribute\"", cmpDesc.getQualifiedName()));
        }

        // another request for getUid will not re-compile again
        Mockito.reset(registry);
        try {
            registry.getUid(null, cmpDesc);
            fail("Expected InvalidDefinitionException");
        } catch (Throwable e) {
            checkExceptionFull(e, InvalidDefinitionException.class,
                    String.format("%s:1,38: Invalid attribute \"invalidAttribute\"", cmpDesc.getQualifiedName()));
        }
        Mockito.verify(registry, Mockito.times(0)).compileDE(Mockito.eq(cmpDesc));
    }

    public void testCompileDef() throws Exception {
        // create test component with 2 explicit dependencies
        DefDescriptor<ComponentDef> cmpDesc1 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDesc2 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(
                ComponentDef.class,
                String.format(baseComponentTag, "",
                        String.format("<%s/><%s/>", cmpDesc1.getDescriptorName(), cmpDesc2.getDescriptorName())));

        // spy on MDR
        final MasterDefRegistryImpl registry = getDefRegistry(true);
        registry.invalidate(null);
        spyOnDefs(registry);

        // get def UID to trigger compileDef, etc.
        String uid = registry.getUid(null, cmpDesc);
        assertNotNull(uid);
        ComponentDef def = registry.getDef(cmpDesc);
        assertNotNull(def);
        Mockito.verify(registry, Mockito.times(1)).compileDE(Mockito.eq(cmpDesc));
        assertCompiledDef(def);

        // check all dependencies
        MockUtil mockUtil = new MockUtil();
        Set<DefDescriptor<?>> dependencies = registry.getDependencies(uid);
        for (DefDescriptor<?> dep : dependencies) {
            Definition depDef = registry.getDef(dep);
            if (mockUtil.isMock(depDef)) {
                // why not controllers?
                if (dep.getDefType().equals(DefType.CONTROLLER)) {
                    continue;
                }
                assertCompiledDef(depDef);
            }
        }
    }

    public void testCompileDefLocalDef() throws Exception {
        // build a mock def
        String descName = String.format("%s:ghost", System.nanoTime());
        ComponentDef def = Mockito.mock(ComponentDef.class);

        Mockito.doReturn(DefDescriptorImpl.getInstance(descName, ComponentDef.class)).when(def).getDescriptor();

        // spy on MDR's registries to spy on defs
        final MasterDefRegistryImpl registry = getDefRegistry(true);
        registry.invalidate(null);
        spyOnDefs(registry);
        registry.addLocalDef(def);

        // get def UID to trigger compileDef, etc.
        String uid = registry.getUid(null, def.getDescriptor());
        assertNotNull(uid);
        Mockito.verify(registry, Mockito.times(1)).compileDE(Mockito.eq(def.getDescriptor()));
        Mockito.doReturn(true).when(def).isValid();
        assertCompiledDef(def);

        // check all dependencies
        MockUtil mockUtil = new MockUtil();
        Set<DefDescriptor<?>> dependencies = registry.getDependencies(uid);
        for (DefDescriptor<?> dep : dependencies) {
            Definition depDef = registry.getDef(dep);
            if (mockUtil.isMock(depDef)) {
                assertCompiledDef(depDef);
            }
        }
    }

    public void testCompileDefOnlyOnce() throws Exception {
        // getDef on registry should compile the def
        String cmpContent = "<aura:component/>";
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class, cmpContent);
        MasterDefRegistryImpl registry = getDefRegistry(true);
        registry.getDef(cmpDesc);
        Mockito.verify(registry, Mockito.times(1)).compileDE(Mockito.eq(cmpDesc));

        // another getDef on same registry should not re-compile the def
        Mockito.reset(registry);
        assertNotNull(registry.getDef(cmpDesc));
        Mockito.verify(registry, Mockito.times(0)).compileDE(Mockito.eq(cmpDesc));

        // another getDef on other registry instance should now compile zero additional times 
        registry = getDefRegistry(true);
        assertNotNull(registry.getDef(cmpDesc));
        Mockito.verify(registry, Mockito.times(0)).compileDE(Mockito.eq(cmpDesc));
    }

    public void testGetDefDescriptorNull() throws Exception {
        MasterDefRegistryImpl registry = getDefRegistry(false);
        assertNull(registry.getDef(null));
    }

    public void testGetDefDescriptorDoesntExist() throws Exception {
        MasterDefRegistryImpl registry = getDefRegistry(false);
        assertNull(registry.getDef(DefDescriptorImpl.getInstance("unknown:soldier", ComponentDef.class)));
    }

    public void testGetDefCachedForChangedDefinition() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        MasterDefRegistryImpl registry = getDefRegistry(false);
        ComponentDef def = registry.getDef(cmpDesc);
        assertNull(def.getAttributeDef("str"));

        // Definition cached for current registry
        registry.getSource(cmpDesc).addOrUpdate(
                "<aura:component><aura:attribute name='str' type='String'/></aura:component>");
        ComponentDef defNew = registry.getDef(cmpDesc);
        assertNull(defNew.getAttributeDef("str"));

        // Definition not cached for new registry
        MasterDefRegistryImpl registryNext = getDefRegistry(false);
        ComponentDef defNext = registryNext.getDef(cmpDesc);
        assertNotNull(defNext.getAttributeDef("str"));
    }

    public void testGetDefCachedForRemovedDefinition() throws Exception {
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>", null);
        MasterDefRegistryImpl registry = getDefRegistry(false);
        ComponentDef def = registry.getDef(cmpDesc);
        assertNotNull(def);

        // Definition cached for current registry
        getAuraTestingUtil().removeSource(cmpDesc);
        ComponentDef defNew = registry.getDef(cmpDesc);
        assertNotNull(defNew);

        // Definition not cached for new registry
        MasterDefRegistryImpl registryNext = getDefRegistry(false);
        ComponentDef defNext = registryNext.getDef(cmpDesc);
        assertNull(defNext);
    }

    /**
     * Circular dependencies case 1: A has inner component B, and B has an explicit dependency on A (via aura:dependency
     * tag)
     */
    public void testCircularDependenciesInnerCmp() throws Exception {
        DefDescriptor<ComponentDef> cmpDescA = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDescB = addSourceAutoCleanup(
                ComponentDef.class,
                String.format("<aura:component><aura:dependency resource=\"%s\"/></aura:component>",
                        cmpDescA.getQualifiedName()));
        updateStringSource(cmpDescA,
                String.format("<aura:component><%s/></aura:component>", cmpDescB.getDescriptorName()));
        // Circular dependency cases should result in identical dependencies
        assertIdenticalDependencies(cmpDescA, cmpDescB);
    }

    /**
     * Circular dependencies case 2: D extends C, and C has explicit dependency on D (via aura:dependency tag)
     */
    public void testCircularDependenciesExtendsCmp() throws Exception {
        DefDescriptor<ComponentDef> cmpDescC = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDescD = addSourceAutoCleanup(ComponentDef.class,
                String.format("<aura:component extends=\"%s\"/>", cmpDescC.getDescriptorName()));
        updateStringSource(cmpDescC, String.format(
                "<aura:component extensible=\"true\"><aura:dependency resource=\"%s\"/></aura:component>",
                cmpDescD.getQualifiedName()));
        // Circular dependency cases should result in identical dependencies
        assertIdenticalDependencies(cmpDescC, cmpDescD);
    }

    /**
     * Circular dependencies case 3: E has dependency on F, and F has dependency on E (both through aura:dependency tag)
     */
    public void testCircularDependenciesDepTag() throws Exception {
        DefDescriptor<ComponentDef> cmpDescE = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDescF = addSourceAutoCleanup(
                ComponentDef.class,
                String.format("<aura:component><aura:dependency resource=\"%s\"/></aura:component>",
                        cmpDescE.getQualifiedName()));
        updateStringSource(
                cmpDescE,
                String.format("<aura:component><aura:dependency resource=\"%s\"/></aura:component>",
                        cmpDescF.getQualifiedName()));
        // Circular dependency cases should result in identical dependencies
        assertIdenticalDependencies(cmpDescE, cmpDescF);
    }

    /**
     * Verify correct dependencies are attached to a component.
     */
    public void testGetDependencies() throws Exception {
        DefDescriptor<ComponentDef> depCmpDesc1 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> depCmpDesc2 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        DefDescriptor<ComponentDef> cmpDesc1 = addSourceAutoCleanup(ComponentDef.class, "<aura:component/>");
        // Manually add dependency to inner component
        DefDescriptor<ComponentDef> cmpDesc2 = addSourceAutoCleanup(ComponentDef.class,
                "<aura:component><aura:dependency resource=\"" + depCmpDesc1.getQualifiedName()
                        + "\"/></aura:component>");
        DefDescriptor<ComponentDef> cmpDesc = addSourceAutoCleanup(
                ComponentDef.class,
                String.format(
                        baseComponentTag,
                        "",
                        String.format("<aura:dependency resource=\"" + depCmpDesc2.getQualifiedName()
                                + "\"/><%s/><%s/>", cmpDesc1.getDescriptorName(), cmpDesc2.getDescriptorName())));

        MasterDefRegistryImpl registry = getDefRegistry(false);
        String uid = registry.getUid(null, cmpDesc);
        Set<DefDescriptor<?>> deps = registry.getDependencies(uid);
        assertTrue("Component should have dependency on aura:component by default",
                checkDependenciesContains(deps, "markup://aura:component"));
        assertTrue("Component should have dependency on aura:rootComponent by default",
                checkDependenciesContains(deps, "markup://aura:rootComponent"));
        assertTrue("Component should not have a dependency on aura:application",
                !checkDependenciesContains(deps, "markup://aura:application"));
        assertTrue("No dependency on self found in Component",
                checkDependenciesContains(deps, cmpDesc.getQualifiedName()));
        assertTrue("Dependency on inner component not found",
                checkDependenciesContains(deps, cmpDesc1.getQualifiedName()));
        assertTrue("Dependency on inner component not found",
                checkDependenciesContains(deps, cmpDesc2.getQualifiedName()));
        assertTrue("Explicitly declared dependency on inner component not found",
                checkDependenciesContains(deps, depCmpDesc1.getQualifiedName()));
        assertTrue("Explicitly declared dependency on top level component not found",
                checkDependenciesContains(deps, depCmpDesc2.getQualifiedName()));
    }

    /**
     * Verify that the file source listener picks up a newly created file and sends out a notification to clear the
     * proper caches.
     */
    // TODO(W-1589052): UnAdaptable since breaks when trying to write/delete files from jars
    @UnAdaptableTest
    @ThreadHostileTest("changes test namespace")
    public void testSourceChangeClearsCachesInDevMode() throws Exception {
        // Make sure we're in Dev mode.
        ContextService contextService = Aura.getContextService();
        if (contextService.isEstablished()) {
            contextService.endContext();
        }
        contextService.startContext(Mode.DEV, Format.JSON, Authentication.AUTHENTICATED);

        MasterDefRegistryImpl mdr = getDefRegistry(false);
        DefDescriptor<ComponentDef> cmpDesc = Aura.getDefinitionService().getDefDescriptor("test:deleteMeAfterTest",
                ComponentDef.class);

        // Make sure it's actually in the caches
        mdr.getDef(cmpDesc);

        // Get the UID before adding the file since getUid() messes with caches
        String uid = mdr.getUid(null, cmpDesc);

        // Tell test to delete added component and directory files at end of test
        Source<?> source = mdr.getSource(cmpDesc);
        File f = new File(source.getUrl().replace("file:", ""));
        deleteFileOnTeardown(f);
        deleteFileOnTeardown(f.getParentFile());

        // Save file to filesystem and wait for source change to clear caches
        BuilderService builderService = Aura.getBuilderService();
        ComponentDef def = builderService.getComponentDefBuilder().setDescriptor(cmpDesc).build();
        Aura.getDefinitionService().save(def);

        // Make sure we actually have something to clear from the cache before verifying it's not in there.
        //if (!isInDefsCache(cmpDesc, mdr)) {
        //    fail("Test setup failure: def not added to MasterDefRegistry cache");
        //}
        assertNotCached(cmpDesc, mdr, uid);
    }

    /**
     * Wait for MasterDefRegistry and CachingDefRegistry caches to be cleared after a source change.
     */
    private void assertNotCached(DefDescriptor<ComponentDef> cmpDesc, MasterDefRegistryImpl mdr, String uid)
            throws Exception {
        long startTime = System.nanoTime();
        long timeoutInMilliseconds = 10000;
        long intervalInMilliseconds = 100;

        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime) < timeoutInMilliseconds) {
            if (isMdrCacheCleared(cmpDesc, mdr, uid)) {
                return;
            }
            Thread.sleep(intervalInMilliseconds);
        }
        fail("Caches did not clear within " + (timeoutInMilliseconds / 1000) + " seconds after a source change");
    }

    /**
     * Return true if DefDescriptor has been removed from MasterDefRegistry static cache. This does not take into
     * account the non-static local cache.
     */
    private boolean isMdrCacheCleared(DefDescriptor<ComponentDef> cmpDesc, MasterDefRegistryImpl mdr, String uid)
            throws Exception {
    	Cache<String, DependencyEntry> dependencies = AuraPrivateAccessor.get(mdr, "depsCache");
        String key = AuraPrivateAccessor.invoke(mdr, "makeGlobalKey", uid, cmpDesc);
        Object cacheReturn = dependencies.getIfPresent(key);

        return cacheReturn == null && !isInDefsCache(cmpDesc, mdr);
    }

    /**
     * Verify caches are cleared after a source change to a component file. In this case only the component def itself
     * should be cleared from the cache.
     */
    @ThreadHostileTest("requires cache to remain stable")
    public void testInvalidateCacheCmpFile() throws Exception {
        MasterDefRegistryImpl mdr = getDefRegistry(false);

        Map<DefType, DefDescriptor<?>> defs = addDefsToCaches(mdr);
        DefDescriptor<?> cmpDef = defs.get(DefType.COMPONENT);
        Aura.getCachingService().notifyDependentSourceChange(Collections.<WeakReference<SourceListener>> emptySet(),
                cmpDef, SourceListener.SourceMonitorEvent.changed, null);

        assertFalse("ComponentDef not cleared from cache", isInDefsCache(defs.get(DefType.COMPONENT), mdr));
        assertTrue("ControllerDef in same bundle as cmp should not be cleared from cache",
                isInDefsCache(defs.get(DefType.CONTROLLER), mdr));
        assertTrue("NamespaceDef should not be cleared from cache", isInDefsCache(defs.get(DefType.NAMESPACE), mdr));
    }

    /**
     * Verify caches are cleared after a source change to a namespace def file. In this case all items in the cache with
     * the same namespace as the def should be cleared.
     */
    @ThreadHostileTest("requires cache to remain stable")
    public void testInvalidateCacheNamespaceFile() throws Exception {
        MasterDefRegistryImpl mdr = getDefRegistry(false);

        Map<DefType, DefDescriptor<?>> defs = addDefsToCaches(mdr);
        DefDescriptor<?> namespaceDef = defs.get(DefType.NAMESPACE);
        Aura.getCachingService().notifyDependentSourceChange(Collections.<WeakReference<SourceListener>> emptySet(),
                namespaceDef, SourceListener.SourceMonitorEvent.changed, null);

        assertFalse("NamespaceDef not cleared from cache", isInDefsCache(defs.get(DefType.NAMESPACE), mdr));
        assertFalse("ComponentDef in same namespace as changed namespaceDef not cleared from cache",
                isInDefsCache(defs.get(DefType.COMPONENT), mdr));
        assertFalse("ControllerDef in same namespace as changed namespaceDef not cleared from cache",
                isInDefsCache(defs.get(DefType.CONTROLLER), mdr));
        assertTrue("ControllerDef in different namespace as changed namespaceDef should not be cleared from cache",
                isInDefsCache(defs.get(DefType.RENDERER), mdr));
    }

    /**
     * Verify caches are cleared after a source change to a Layouts def file. In this case all items in the layouts
     * bundle should be cleared.
     */
    @ThreadHostileTest("requires cache to remain stable")
    public void testInvalidateCacheLayoutsFile() throws Exception {
        MasterDefRegistryImpl mdr = getDefRegistry(false);

        Map<DefType, DefDescriptor<?>> defs = addDefsToCaches(mdr);
        DefDescriptor<?> layoutsDef = defs.get(DefType.LAYOUTS);
        Aura.getCachingService().notifyDependentSourceChange(Collections.<WeakReference<SourceListener>> emptySet(),
                layoutsDef, SourceListener.SourceMonitorEvent.changed, null);

        assertFalse("LayoutsDef not cleared from cache", isInDefsCache(defs.get(DefType.LAYOUTS), mdr));
        assertFalse("ApplicationDef in same bundle as LayoutsDef not cleared from cache",
                isInDefsCache(defs.get(DefType.APPLICATION), mdr));
        assertTrue("NamespaceDef should not be cleared from cache on LayoutsDef source change",
                isInDefsCache(defs.get(DefType.NAMESPACE), mdr));
        assertTrue("Cmp in same namespace but different bundle as Layouts def should not be cleared from cache",
                isInDefsCache(defs.get(DefType.COMPONENT), mdr));
    }

    /**
     * Create a set of DefDescriptors and add them to the MDR caches by calling getDef() on them.
     * 
     * @return List of DefDescriptors that have been added to the mdr caches.
     */
    private Map<DefType, DefDescriptor<?>> addDefsToCaches(MasterDefRegistryImpl mdr) throws Exception {
        DefDescriptor<NamespaceDef> namespaceDef = DefDescriptorImpl.getInstance("test", NamespaceDef.class);
        DefDescriptor<ComponentDef> cmpDef = DefDescriptorImpl.getInstance("test:test_button",
                ComponentDef.class);
        DefDescriptor<ControllerDef> cmpControllerDef = DefDescriptorImpl.getInstance(
                "js://test.test_button", ControllerDef.class);
        DefDescriptor<RendererDef> otherNamespaceDef = DefDescriptorImpl.getInstance(
                "js://gvpTest.labelProvider", RendererDef.class);
        DefDescriptor<ApplicationDef> appInLayoutsBundleDef = DefDescriptorImpl.getInstance("test:layouts",
                ApplicationDef.class);
        DefDescriptor<LayoutsDef> layoutsDef = DefDescriptorImpl.getInstance("test:layouts",
                LayoutsDef.class);

        Map<DefType, DefDescriptor<?>> map = new HashMap<DefType, DefDescriptor<?>>();
        map.put(DefType.NAMESPACE, namespaceDef);
        map.put(DefType.COMPONENT, cmpDef);
        map.put(DefType.CONTROLLER, cmpControllerDef);
        map.put(DefType.RENDERER, otherNamespaceDef);
        map.put(DefType.APPLICATION, appInLayoutsBundleDef);
        map.put(DefType.LAYOUTS, layoutsDef);

        for (DefType defType : map.keySet()) {
            DefDescriptor<?> dd = map.get(defType);
            dd.getDef();
        }

        return map;
    }
    
    private boolean isInDescriptorFilterCache(DescriptorFilter filter, Set<DefDescriptor<?>> results, MasterDefRegistryImpl mdr) throws Exception {
        //taking the long road in determining what is in the cache because the current key implementation for
        //the descriptor cache is difficult to recreate.
        Cache<String, Set<DefDescriptor<?>>> cache = AuraPrivateAccessor.get(mdr, "descriptorFilterCache");
        for (String key : cache.getKeySet()) {
            if (key.startsWith(filter.toString() + "|")) {
                return results.equals(cache.getIfPresent(key));
            }
        }
        return false;
    }

    private boolean isInDepsCache(DefDescriptor<?> dd, MasterDefRegistryImpl mdr) throws Exception {
        Cache<String, ?> cache = AuraPrivateAccessor.get(mdr, "depsCache");
        String ddKey = dd.getDescriptorName().toLowerCase();
        for (String key : cache.getKeySet()) {
        	if (key.endsWith(ddKey)) {
        		return cache.getIfPresent(key) != null;
        	}
        }
        return false;
    }

    private boolean isInDefsCache(DefDescriptor<?> dd, MasterDefRegistryImpl mdr) throws Exception {
    	Cache<DefDescriptor<?>, Optional<? extends Definition>> cache = AuraPrivateAccessor.get(mdr, "defsCache");
        return null != cache.getIfPresent(dd);
    }

    private boolean isInExistsCache(DefDescriptor<?> dd, MasterDefRegistryImpl mdr) throws Exception {
        Cache<DefDescriptor<?>, Boolean> cache = AuraPrivateAccessor.get(mdr, "existsCache");
        return Boolean.TRUE == cache.getIfPresent(dd);
    }

    /**
     * Verify basic functionality of MasterDefRegistryImpl.getClientLibraries.
     * The same methods are test in ClientLibraryServiceImplTest, where we use ClientLibraryService.getUrls()
     * @throws Exception
     */
    public void testGetClientLibraries()throws Exception{
        MasterDefRegistry mdr = getAuraMDR();
        List<ClientLibraryDef> libDefs = mdr.getClientLibraries(null);
        assertNull(libDefs);
        
        DefDescriptor<ApplicationDef> appDesc = Aura.getDefinitionService().getDefDescriptor(
                "clientLibraryTest:testDependencies", ApplicationDef.class);
        AuraContext cntx = Aura.getContextService().getCurrentContext();
        cntx.setApplicationDescriptor(appDesc);
        Aura.getDefinitionService().updateLoaded(appDesc);
        
        libDefs = mdr.getClientLibraries(cntx.getUid(appDesc));

        //13 from clientLibraryTest:testDependencies and its dependencies + 4 from aura:component
        //Update this number when you add new aura:clientLibrary tags to these components
        assertEquals(17, libDefs.size());
    }
    
    public void testAssertAccess_IfGlobalAccessThenPassesCheck() throws Exception{
        when(globalDef.getAccess()).thenReturn(defAccess);
        when(defAccess.isGlobal()).thenReturn(true);
        MasterDefRegistry mdr = getAuraMDR();
        mdr.assertAccess(null, globalDef);
        
        verify(globalDef).getAccess();
        verify(defAccess).isGlobal();
    }
    
    
    public void testAssertAccess_IfReferencedByUnsecuredPrefixThenPassesCheck()throws Exception{
        when(globalDef.getAccess()).thenReturn(defAccess);
        when(defAccess.isGlobal()).thenReturn(false);
        when(defAccess.requiresAuthentication()).thenReturn(true);
        when(referencingDesc.getPrefix()).thenReturn("aura");
        MasterDefRegistry mdr = getAuraMDR();
        mdr.assertAccess(referencingDesc, globalDef);
        
        verify(referencingDesc).getPrefix();
    }
    
    /**
     * Verify that if access cache has a reason to block access, then MDR throws NoAccessException.
     * @throws Exception
     */
    public void testAssertAccess_UsesCachedValueIfPresent_BlockAccess()throws Exception{
        DefDescriptor<ComponentDef> desc = addSourceAutoCleanup(ComponentDef.class, String.format(baseComponentTag, "", ""));
        when(mockAccessCheckCache.getIfPresent(anyString())).thenReturn("Error");
        MasterDefRegistryImpl mdr = (MasterDefRegistryImpl)getAuraMDR();
        try{
            mdr.assertAccess(null, desc.getDef(), mockAccessCheckCache);
            fail("Expected NoAccessException because accessCache has reason to block def");
        }catch(Exception e){
            this.assertExceptionMessageStartsWith(e, NoAccessException.class, "Error");
        }
        verify(mockAccessCheckCache).getIfPresent(anyString());
    }
    
    /**
     * Verify that if access cache doesn't have any message to block access, then access checks passes through.
     * @throws Exception
     */
    public void testAssertAccess_UsesCachedValueIfPresent_AllowAccess()throws Exception{
        DefDescriptor<ComponentDef> desc = addSourceAutoCleanup(ComponentDef.class, String.format(baseComponentTag, "", ""));
        when(mockAccessCheckCache.getIfPresent(anyString())).thenReturn("");
        MasterDefRegistryImpl mdr = (MasterDefRegistryImpl)getAuraMDR();
        mdr.assertAccess(null, desc.getDef(), mockAccessCheckCache);
        
        verify(mockAccessCheckCache).getIfPresent(anyString());
    }
    
    public void testAssertAccess_StoreAccessInfoInCacheIfNotPresent()throws Exception{
        DefDescriptor<ComponentDef> desc = getAuraTestingUtil().addSourceAutoCleanup(ComponentDef.class, String.format(baseComponentTag, "", ""), StringSourceLoader.DEFAULT_CUSTOM_NAMESPACE + ":testComp", false);
        when(mockAccessCheckCache.getIfPresent(anyString())).thenReturn(null);
        
        MasterDefRegistryImpl mdr = (MasterDefRegistryImpl)getAuraMDR();
        mdr.assertAccess(desc, desc.getDef(), mockAccessCheckCache);

        verify(mockAccessCheckCache).put(anyString(),anyString());
        
        mdr.assertAccess(desc, desc.getDef(), mockAccessCheckCache);
        verify(mockAccessCheckCache, times(2)).getIfPresent(anyString());
    }
    
    public void testExistsCache() throws Exception {
        ConfigAdapter configAdapter = Aura.getConfigAdapter();
        MasterDefRegistry mdr = getAuraMDR();
        MasterDefRegistryImpl mdri = (MasterDefRegistryImpl)mdr;
        Map<DefType, DefDescriptor<?>> defs = addDefsToCaches(mdri);
        Map<DefType, DefDescriptor<?>> nonPrivDefs = addNonPriveledgedDefsToMDR(mdri);
        for (DefDescriptor<?> dd : defs.values()) {
            assertTrue(dd + " should exist.", dd.exists());
        }
        for (DefDescriptor<?> dd : nonPrivDefs.values()) {
            assertTrue(dd + " should exist.", dd.exists());
        }

        DefDescriptor<?> nsDef = defs.get(DefType.NAMESPACE);
        DefDescriptor<?> layoutDef = defs.get(DefType.LAYOUTS);
        DefDescriptor<?> rendererDef = defs.get(DefType.RENDERER);
        DefDescriptor<?> appDef = defs.get(DefType.APPLICATION);
        DefDescriptor<?> controllerDef = defs.get(DefType.CONTROLLER);
        DefDescriptor<?> cmpDef = defs.get(DefType.COMPONENT);

        DefDescriptor<?> npNSDef = nonPrivDefs.get(DefType.NAMESPACE);
        DefDescriptor<?> npLayoutDef = nonPrivDefs.get(DefType.LAYOUTS);
        DefDescriptor<?> npRendererDef = nonPrivDefs.get(DefType.RENDERER);
        DefDescriptor<?> nsAppDef = nonPrivDefs.get(DefType.APPLICATION);
        DefDescriptor<?> nsControllerDef = nonPrivDefs.get(DefType.CONTROLLER);
        DefDescriptor<?> nsCmpDef = nonPrivDefs.get(DefType.COMPONENT);
        
        //only picking 3 defs to test the ns as they are mostly dupes
        assertTrue(nsDef.getNamespace() + "  should have been isPriveleged", configAdapter.isPrivilegedNamespace(nsDef.getNamespace()));
        assertTrue(layoutDef.getNamespace() + "  should have been isPriveleged", configAdapter.isPrivilegedNamespace(layoutDef.getNamespace()));
        assertTrue(rendererDef.getNamespace() + "  should have been isPriveleged", configAdapter.isPrivilegedNamespace(rendererDef.getNamespace()));
        
        assertFalse(npLayoutDef.getNamespace() + "  should not have been isPriveleged", configAdapter.isPrivilegedNamespace(npLayoutDef.getNamespace()));
        assertFalse(npNSDef.getNamespace() + "  should not have been isPriveleged", configAdapter.isPrivilegedNamespace(npNSDef.getNamespace()));
        assertFalse(npRendererDef.getNamespace() + "  should not have been isPriveleged", configAdapter.isPrivilegedNamespace(npRendererDef.getNamespace()));
        
        MasterDefRegistry mdr2 = restartContextGetNewMDR();
        MasterDefRegistryImpl mdri2 = (MasterDefRegistryImpl)mdr2;
        
        //objects wont be in eists cache yet, just defsCache, need to call exists to prime exists cache

        for (DefDescriptor<?> dd : defs.values()) {
            assertTrue(dd + " should exist.", dd.exists());
        }
        assertTrue("nsDef is in cache", isInExistsCache(nsDef, mdri2));
        assertTrue("LayoutsDef is in cache", isInExistsCache(defs.get(DefType.LAYOUTS), mdri2));
        assertTrue("RendererDef is in cache", isInExistsCache(rendererDef, mdri2));
        assertTrue("app is in cache", isInExistsCache(appDef, mdri2));
        assertTrue("controller is in cache", isInExistsCache(controllerDef, mdri2));
        assertTrue("cmp is in cache", isInExistsCache(cmpDef, mdri2));
        
        assertFalse("npNSDef is not in cache", isInExistsCache(npNSDef, mdri2));
        assertFalse("npLayoutsDef is not in cache", isInExistsCache(npLayoutDef, mdri2));
        assertFalse("npRendererDef is notin cache", isInExistsCache(npRendererDef, mdri2));
        assertFalse("nsApp is not in cache", isInExistsCache(nsAppDef, mdri2));
        assertFalse("nsController is not in cache", isInExistsCache(nsControllerDef, mdri2));
        assertFalse("nsCmp is not in cache", isInExistsCache(nsCmpDef, mdri2));     
        
        
        MasterDefRegistry mdr3 = restartContextGetNewMDR();
        MasterDefRegistryImpl mdri3 = (MasterDefRegistryImpl)mdr3;
        
        assertTrue("nsDef is in cache", isInExistsCache(nsDef, mdri3));
        assertTrue("LayoutsDef is in cache", isInExistsCache(defs.get(DefType.LAYOUTS), mdri3));
        assertTrue("RendererDef is in cache", isInExistsCache(rendererDef, mdri3));
        assertTrue("app is in cache", isInExistsCache(appDef, mdri3));
        assertTrue("controller is in cache", isInExistsCache(controllerDef, mdri3));
        assertTrue("cmp is in cache", isInExistsCache(cmpDef, mdri3));
        
        assertFalse("npNSDef is not in cache", isInExistsCache(npNSDef, mdri3));
        assertFalse("npLayoutsDef is not in cache", isInExistsCache(npLayoutDef, mdri3));
        assertFalse("npRendererDef is notin cache", isInExistsCache(npRendererDef, mdri3));
        assertFalse("nsApp is not in cache", isInExistsCache(nsAppDef, mdri3));
        assertFalse("nsController is not in cache", isInExistsCache(nsControllerDef, mdri3));
        assertFalse("nsCmp is not in cache", isInExistsCache(nsCmpDef, mdri3));
    }
    
    public void testDefsCache() throws Exception {
        ConfigAdapter configAdapter = Aura.getConfigAdapter();
        MasterDefRegistry mdr = getAuraMDR();
        MasterDefRegistryImpl mdri = (MasterDefRegistryImpl)mdr;
        Map<DefType, DefDescriptor<?>> defs = addDefsToCaches(mdri);
        Map<DefType, DefDescriptor<?>> nonPrivDefs = addNonPriveledgedDefsToMDR(mdri);

        DefDescriptor<?> nsDef = defs.get(DefType.NAMESPACE);
        DefDescriptor<?> layoutDef = defs.get(DefType.LAYOUTS);
        DefDescriptor<?> rendererDef = defs.get(DefType.RENDERER);
        DefDescriptor<?> appDef = defs.get(DefType.APPLICATION);
        DefDescriptor<?> controllerDef = defs.get(DefType.CONTROLLER);
        DefDescriptor<?> cmpDef = defs.get(DefType.COMPONENT);

        DefDescriptor<?> npNSDef = nonPrivDefs.get(DefType.NAMESPACE);
        DefDescriptor<?> npLayoutDef = nonPrivDefs.get(DefType.LAYOUTS);
        DefDescriptor<?> npRendererDef = nonPrivDefs.get(DefType.RENDERER);
        DefDescriptor<?> nsAppDef = nonPrivDefs.get(DefType.APPLICATION);
        DefDescriptor<?> nsControllerDef = nonPrivDefs.get(DefType.CONTROLLER);
        DefDescriptor<?> nsCmpDef = nonPrivDefs.get(DefType.COMPONENT);
        
        //only picking 3 defs to test the ns as they are mostly dupes
        assertTrue(nsDef.getNamespace() + "  should have been isPriveleged", configAdapter.isPrivilegedNamespace(nsDef.getNamespace()));
        assertTrue(layoutDef.getNamespace() + "  should have been isPriveleged", configAdapter.isPrivilegedNamespace(layoutDef.getNamespace()));
        assertTrue(rendererDef.getNamespace() + "  should have been isPriveleged", configAdapter.isPrivilegedNamespace(rendererDef.getNamespace()));
        
        assertFalse(npLayoutDef.getNamespace() + "  should not have been isPriveleged", configAdapter.isPrivilegedNamespace(npLayoutDef.getNamespace()));
        assertFalse(npNSDef.getNamespace() + "  should not have been isPriveleged", configAdapter.isPrivilegedNamespace(npNSDef.getNamespace()));
        assertFalse(npRendererDef.getNamespace() + "  should not have been isPriveleged", configAdapter.isPrivilegedNamespace(npRendererDef.getNamespace()));
        

        assertTrue("nsDef is in cache", isInDefsCache(nsDef, mdri));
        assertTrue("LayoutsDef is in cache", isInDefsCache(layoutDef, mdri));
        assertTrue("RendererDef is in cache", isInDefsCache(rendererDef, mdri));
        assertTrue("app is in cache", isInDefsCache(appDef, mdri));
        assertTrue("controller is in cache", isInDefsCache(controllerDef, mdri));
        assertTrue("cmp is in cache", isInDefsCache(cmpDef, mdri));
        
        assertFalse("npNSDef is not in cache", isInDefsCache(npNSDef, mdri));
        assertFalse("npLayoutsDef is not in cache", isInDefsCache(npLayoutDef, mdri));
        assertFalse("npRendererDef is not in cache", isInDefsCache(npRendererDef, mdri));
        assertFalse("nsApp is not in cache", isInDefsCache(nsAppDef, mdri));
        assertFalse("nsController is not in cache", isInDefsCache(nsControllerDef, mdri));
        assertFalse("nsCmp is not in cache", isInDefsCache(nsCmpDef, mdri));
        
        MasterDefRegistry mdr2 = restartContextGetNewMDR();
        MasterDefRegistryImpl mdri2 = (MasterDefRegistryImpl)mdr2;
        
        assertTrue("nsDef is in cache", isInDefsCache(nsDef, mdri2));
        assertTrue("LayoutsDef is in cache", isInDefsCache(defs.get(DefType.LAYOUTS), mdri2));
        assertTrue("RendererDef is in cache", isInDefsCache(rendererDef, mdri2));
        assertTrue("app is in cache", isInDefsCache(appDef, mdri2));
        assertTrue("controller is in cache", isInDefsCache(controllerDef, mdri2));
        assertTrue("cmp is in cache", isInDefsCache(cmpDef, mdri2));
        
        assertFalse("npNSDef is not in cache", isInDefsCache(npNSDef, mdri2));
        assertFalse("npLayoutsDef is not in cache", isInDefsCache(npLayoutDef, mdri2));
        assertFalse("npRendererDef is notin cache", isInDefsCache(npRendererDef, mdri2));
        assertFalse("nsApp is not in cache", isInDefsCache(nsAppDef, mdri2));
        assertFalse("nsController is not in cache", isInDefsCache(nsControllerDef, mdri2));
        assertFalse("nsCmp is not in cache", isInDefsCache(nsCmpDef, mdri2));
    }
    
    public void testDescriptorFilterCache() throws Exception {
        ConfigAdapter configAdapter = Aura.getConfigAdapter();
        MasterDefRegistry mdr = getAuraMDR();
        MasterDefRegistryImpl mdri = (MasterDefRegistryImpl)mdr;
        Map<DefType, DefDescriptor<?>> defs = addDefsToCaches(mdri);
        Map<DefType, DefDescriptor<?>> nonPrivDefs = addNonPriveledgedDefsToMDR(mdri);

        DefDescriptor<?> nsDef = defs.get(DefType.NAMESPACE);
        DefDescriptor<?> layoutDef = defs.get(DefType.LAYOUTS);
        DefDescriptor<?> rendererDef = defs.get(DefType.RENDERER);

        DefDescriptor<?> npNSDef = nonPrivDefs.get(DefType.NAMESPACE);
        DefDescriptor<?> npLayoutDef = nonPrivDefs.get(DefType.LAYOUTS);
        DefDescriptor<?> npRendererDef = nonPrivDefs.get(DefType.RENDERER);
        
        //only picking 3 defs to test the ns as they are mostly dupes
        assertTrue(nsDef.getNamespace() + "  should have been isPriveleged", configAdapter.isPrivilegedNamespace(nsDef.getNamespace()));
        assertTrue(layoutDef.getNamespace() + "  should have been isPriveleged", configAdapter.isPrivilegedNamespace(layoutDef.getNamespace()));
        assertTrue(rendererDef.getNamespace() + "  should have been isPriveleged", configAdapter.isPrivilegedNamespace(rendererDef.getNamespace()));
        
        assertFalse(npLayoutDef.getNamespace() + "  should not have been isPriveleged", configAdapter.isPrivilegedNamespace(npLayoutDef.getNamespace()));
        assertFalse(npNSDef.getNamespace() + "  should not have been isPriveleged", configAdapter.isPrivilegedNamespace(npNSDef.getNamespace()));
        assertFalse(npRendererDef.getNamespace() + "  should not have been isPriveleged", configAdapter.isPrivilegedNamespace(npRendererDef.getNamespace()));
        
        DescriptorFilter filter = new DescriptorFilter("*://test:*");
        Set<DefDescriptor<?>> results = mdr.find(filter);
        assertTrue("results should be cached", isInDescriptorFilterCache(filter, results, mdri));
        DescriptorFilter filter2 = new DescriptorFilter("*://gvpTest:*");
        Set<DefDescriptor<?>> results2 = mdr.find(filter2);
        assertTrue("results2 should be cached", isInDescriptorFilterCache(filter2, results2, mdri));
        
        DescriptorFilter filter3 = new DescriptorFilter("*://cstring:*");
        Set<DefDescriptor<?>> results3 = mdr.find(filter3);
        assertFalse("results3 should not be cached", isInDescriptorFilterCache(filter3, results3, mdri));
        DescriptorFilter filter4 = new DescriptorFilter("*://cstring1:*");
        Set<DefDescriptor<?>> results4 = mdr.find(filter4);
        assertFalse("results4 should be cached", isInDescriptorFilterCache(filter4, results4, mdri));
        
        DescriptorFilter filter5 = new DescriptorFilter("*://*:*");
        Set<DefDescriptor<?>> results5 = mdr.find(filter5);
        assertFalse("results5 should not be cached", isInDescriptorFilterCache(filter5, results5, mdri));
//        DescriptorFilter filter6 = new DescriptorFilter("*://*test:*");
//        Set<DefDescriptor<?>> results6 = mdr.find(filter6);
//        assertFalse("results6 should be cached", isInDescriptorFilterCache(filter6, results6, mdri));
      
        
        MasterDefRegistry mdr2 = restartContextGetNewMDR();
        MasterDefRegistryImpl mdri2 = (MasterDefRegistryImpl)mdr2;
        assertTrue("results should still be cached", isInDescriptorFilterCache(filter, results, mdri2));
        assertTrue("results2 should still be cached", isInDescriptorFilterCache(filter2, results2, mdri2));
        assertFalse("results3 should not be cached", isInDescriptorFilterCache(filter3, results3, mdri2));
        assertFalse("results4 should not be cached", isInDescriptorFilterCache(filter4, results4, mdri2));
        assertFalse("results5 should not be cached", isInDescriptorFilterCache(filter5, results5, mdri2));
//        assertFalse("results6 should not be cached", isInDescriptorFilterCache(filter6, results6, mdri2));
    }
    
    public void testDepsCache() throws Exception {
    	String unprivilegedNamespace = getAuraTestingUtil().getNonce("alien");

        // in privileged namespace
        DefDescriptor<ComponentDef> privilegedCmp = getAuraTestingUtil().addSourceAutoCleanup(
        		ComponentDef.class, String.format(baseComponentTag,"access='global'",""), null, true);
        // in unprivileged namespace depending on privileged cmp
        DefDescriptor<ComponentDef> unprivilegedCmp = getAuraTestingUtil().addSourceAutoCleanup(
        		DefDescriptorImpl.getInstance(String.format("markup://%s:cmp", unprivilegedNamespace), ComponentDef.class),
        		String.format(baseComponentTag, "access='global'", String.format("<%s/>", privilegedCmp.getDescriptorName())),
        		false);
        
        // in privileged namespace depending on unprivileged cmp
		DefDescriptor<ComponentDef> privilegedRoot = getAuraTestingUtil().addSourceAutoCleanup(ComponentDef.class,
				String.format(baseComponentTag, "access='global'", String.format("<%s/>", unprivilegedCmp.getDescriptorName())),
				null, true);
        
        ConfigAdapter configAdapter = Aura.getConfigAdapter();
		assertTrue(configAdapter.isPrivilegedNamespace(privilegedCmp.getNamespace()));
		assertFalse(configAdapter.isPrivilegedNamespace(unprivilegedCmp.getNamespace()));
		assertTrue(configAdapter.isPrivilegedNamespace(privilegedRoot.getNamespace()));
		
        MasterDefRegistry mdr = Aura.getContextService().getCurrentContext().getDefRegistry();
        MasterDefRegistryImpl mdri = (MasterDefRegistryImpl)mdr;
        mdr.getDef(privilegedCmp);
        assertTrue(isInDepsCache(privilegedCmp, mdri));
        assertFalse(isInDepsCache(unprivilegedCmp, mdri));
        assertFalse(isInDepsCache(privilegedRoot, mdri));
        
        mdr.invalidate(DefDescriptorImpl.getInstance("aura:component", ComponentDef.class)); // invalidate the world
        mdr.getDef(unprivilegedCmp);
        assertFalse(isInDepsCache(privilegedCmp, mdri));
        assertFalse(isInDepsCache(unprivilegedCmp, mdri));
        assertFalse(isInDepsCache(privilegedRoot, mdri));
        
        mdr.invalidate(DefDescriptorImpl.getInstance("aura:component", ComponentDef.class)); // invalidate the world
		try {
			mdr.getDef(privilegedRoot);
			fail("Shouldn't be able to have a privileged cmp depend on an unprivileged cmp");
		} catch (Throwable t) {
			this.assertExceptionMessageStartsWith(t,
					DefinitionNotFoundException.class, String.format(
							"No COMPONENT named %s found",
							unprivilegedCmp.getQualifiedName()));
		}
    }
    
    public void testJavaProtocolIsCached() throws Exception {
        DefDescriptor<ControllerDef> controllerDef = DefDescriptorImpl.getInstance("java://org.auraframework.java.controller.TestController", ControllerDef.class);
        String prefix = controllerDef.getPrefix();
        assertEquals(prefix, "java");
        

        ConfigAdapter configAdapter = Aura.getConfigAdapter();
        assertFalse(configAdapter.isPrivilegedNamespace(controllerDef.getNamespace()));
        
        MasterDefRegistry mdr = Aura.getContextService().getCurrentContext().getDefRegistry();
        mdr.getDef(controllerDef);
        MasterDefRegistryImpl mdri = (MasterDefRegistryImpl)mdr;
        assertTrue(isInDepsCache(controllerDef, mdri));
    }

    private MasterDefRegistry restartContextGetNewMDR() {
        //simulate new request
        MasterDefRegistry mdr = getAuraMDR();
        AuraContext ctx = Aura.getContextService().getCurrentContext();
        Mode mode = ctx.getMode();
        Format format = ctx.getFormat();
        Authentication access = ctx.getAccess();
        Aura.getContextService().endContext();

        Aura.getContextService().startContext(mode, format, access);
        MasterDefRegistry mdr2 = getAuraMDR();
        assertFalse("MasterDefRegistry should be different after restart of context", mdr == mdr2);
        return mdr2;
    }
    
    /**
     * Create a set of DefDescriptors and add them to the MDR caches by calling getDef() on them.
     * 
     * @return List of DefDescriptors that have been added to the mdr caches.
     */
    private Map<DefType, DefDescriptor<?>> addNonPriveledgedDefsToMDR(MasterDefRegistryImpl mdr) throws Exception {
        DefDescriptor<NamespaceDef> namespaceDef = DefDescriptorImpl.getInstance("cstring", NamespaceDef.class);
        DefDescriptor<ComponentDef> cmpDef = getAuraTestingUtil().addSourceAutoCleanup(ComponentDef.class, 
                "<aura:component>"
                        + "<aura:attribute name='label' type='String'/>"
                        + "<aura:attribute name='class' type='String'/>"
                        + "<aura:registerevent name='press' type='test:test_press'/>"
                        + "<div onclick='{!c.press}' class='{!v.class}'>{!v.label}</div>"
                        + "</aura:component>", "cstring:test_button", false);
        DefDescriptor<ControllerDef> cmpControllerDef = getAuraTestingUtil().addSourceAutoCleanup(ControllerDef.class,
                "{    press : function(cmp, event){        cmp.getEvent('press').fire();    }}",
                "cstring.test_button", false);
        DefDescriptor<RendererDef> otherNamespaceDef = getAuraTestingUtil().addSourceAutoCleanup(RendererDef.class,
                "({render: function(cmp) {"
                        + "var gvp = $A.getGlobalValueProviders();"
                        + "cmp.getValue('v.simplevalue1').setValue(gvp.getValue('$Label' + '.Related_Lists' + '.task_mode_today', cmp));"
                        + "cmp.getValue('v.simplevalue2').setValue(gvp.getValue('$Label.DOESNT.EXIST', cmp));"
                        + "cmp.getValue('v.simplevalue3').setValue(gvp.getValue('$Label.Related_Lists.DOESNTEXIST', cmp));"
                        + "// Both section and name are required. This request will return undefined and no action is requested."
                        + "cmp.getValue('v.simplevalue4').setValue(gvp.getValue('$Label.DOESNTEXIST', cmp));"
                        + "// These requests are here to test that there are no multiple action requests for the same $Label"
                        + "// See LabelValueProviderUITest.java"
                        + "var tmt = gvp.getValue('$Label.Related_Lists.task_mode_today', cmp);"
                        + "tmt = gvp.getValue('$Label.Related_Lists.task_mode_today', cmp);"
                        + "tmt = gvp.getValue('$Label.Related_Lists.task_mode_today', cmp);"
                        + "tmt = gvp.getValue('$Label.Related_Lists.task_mode_today', cmp);"
                        + "tmt = gvp.getValue('$Label.Related_Lists.task_mode_today', cmp);"
                        + "return this.superRender();"
                        + "}})",
                "cstring1.labelProvider", false);
        DefDescriptor<ApplicationDef> appInLayoutsBundleDef = getAuraTestingUtil().addSourceAutoCleanup(ApplicationDef.class,
                "<aura:application>    before    <div aura:id='content'/>    after</aura:application>",
                "cstring:layouts", false);
        DefDescriptor<LayoutsDef> layoutsDef = getAuraTestingUtil().addSourceAutoCleanup(LayoutsDef.class,
                "<aura:layouts default='def'>"
                        + "<aura:layout name='def'>"
                        + "<aura:layoutItem container='target' action='{!c.act}'/>"
                        + "</aura:layout>"
                        + "</aura:layouts>", "cstring:nplayout", false);

        Map<DefType, DefDescriptor<?>> map = new HashMap<DefType, DefDescriptor<?>>();
        map.put(DefType.NAMESPACE, namespaceDef);
        map.put(DefType.COMPONENT, cmpDef);
        map.put(DefType.CONTROLLER, cmpControllerDef);
        map.put(DefType.RENDERER, otherNamespaceDef);
        map.put(DefType.APPLICATION, appInLayoutsBundleDef);
        map.put(DefType.LAYOUTS, layoutsDef);

        for (DefType defType : map.keySet()) {
            DefDescriptor<?> dd = map.get(defType);
            dd.getDef();
        }

        return map;
    }
    
    private MasterDefRegistry getAuraMDR(){
        return Aura.getContextService().getCurrentContext().getDefRegistry();
    }
}
