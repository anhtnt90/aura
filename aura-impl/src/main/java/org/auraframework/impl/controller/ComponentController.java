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
package org.auraframework.impl.controller;

import java.util.*;

import javax.inject.Inject;

import org.auraframework.adapter.ExceptionAdapter;
import org.auraframework.annotations.Annotations.ServiceComponent;
import org.auraframework.def.*;
import org.auraframework.ds.servicecomponent.Controller;
import org.auraframework.impl.java.controller.JavaAction;
import org.auraframework.impl.javascript.controller.JavascriptPseudoAction;
import org.auraframework.instance.*;
import org.auraframework.service.*;
import org.auraframework.system.Annotations.AuraEnabled;
import org.auraframework.system.Annotations.Key;
import org.auraframework.system.*;
import org.auraframework.throwable.quickfix.QuickFixException;

import com.google.common.collect.Lists;

@org.springframework.stereotype.Component("org.auraframework.impl.controller.ComponentController")
@ServiceComponent
public class ComponentController implements Controller {

    private InstanceService instanceService;
    private ExceptionAdapter exceptionAdapter;
    private DefinitionService definitionService;
    private ContextService contextService;

    /**
     * A Java exception representing a <em>Javascript</em> error condition, as
     * reported from client to server for forensic logging.
     *
     * @since 194
     */
    public static class AuraClientException extends Exception {
        private static final long serialVersionUID = -5884312216684971013L;

        private final Action action;
        private final String jsStack;

        public AuraClientException(String desc, String id, String message, String jsStack,
                                   InstanceService instanceService, ExceptionAdapter exceptionAdapter) {
            super(message);
            Action action = null;
            if (desc != null && id != null) {
                try {
                    action = instanceService.getInstance(desc, ActionDef.class);
                } catch (QuickFixException e) {
                    // Uh... okay, we fell over running an action we now can't even define.
                }
                if (action instanceof JavascriptPseudoAction) {
                    JavascriptPseudoAction jpa = (JavascriptPseudoAction)action;
                    jpa.setId(id);
                    jpa.addError(this);
                } else if (action instanceof JavaAction) {
                    JavaAction ja = (JavaAction)action;
                    ja.setId(id);
                    ja.addException(this, Action.State.ERROR, false, false, exceptionAdapter);
                }
            }

            this.action = action;
            this.jsStack = jsStack;
        }

        public Action getOriginalAction() {
            return action;
        }

        public String getClientStack() {
            return jsStack;
        }

    }

    @AuraEnabled
    public Boolean loadLabels() throws QuickFixException {
        AuraContext ctx = contextService.getCurrentContext();
        Map<DefDescriptor<? extends Definition>, Definition> defMap;

        ctx.getDefRegistry().getDef(ctx.getApplicationDescriptor());
        defMap = ctx.getDefRegistry().filterRegistry(null);
        for (Map.Entry<DefDescriptor<? extends Definition>, Definition> entry : defMap.entrySet()) {
            Definition def = entry.getValue();
            if (def != null) {
                def.retrieveLabels();
            }
        }
        return Boolean.TRUE;
    }
    
    public static Boolean loadLabels(ContextService contextService) throws QuickFixException {
        AuraContext ctx = contextService.getCurrentContext();
        Map<DefDescriptor<? extends Definition>, Definition> defMap;

        ctx.getDefRegistry().getDef(ctx.getApplicationDescriptor());
        defMap = ctx.getDefRegistry().filterRegistry(null);
        for (Map.Entry<DefDescriptor<? extends Definition>, Definition> entry : defMap.entrySet()) {
            Definition def = entry.getValue();
            if (def != null) {
                def.retrieveLabels();
            }
        }
        return Boolean.TRUE;
    }

    private <D extends BaseComponentDef, T extends BaseComponent<D, T>>
        T getBaseComponent(Class<T> type, Class<D> defType, String name,
                Map<String, Object> attributes, Boolean loadLabels) throws QuickFixException {

        DefDescriptor<D> desc = definitionService.getDefDescriptor(name, defType);
        definitionService.updateLoaded(desc);
        T component = instanceService.getInstance(desc, attributes);
        if (Boolean.TRUE.equals(loadLabels)) {
            this.loadLabels();
        }
        return component;
    }

    // Not aura enabled, but called from code. This is probably bad practice.
    public Component getComponent(String name, Map<String, Object> attributes) throws QuickFixException {
        return  getBaseComponent(Component.class, ComponentDef.class, name, attributes, false);
    }

    @AuraEnabled
    public Component getComponent(@Key(value = "name", loggable = true) String name,
                                  @Key("attributes") Map<String, Object> attributes,
                                  @Key(value = "chainLoadLabels", loggable = true) Boolean loadLabels) throws QuickFixException {
        return  getBaseComponent(Component.class, ComponentDef.class, name, attributes, loadLabels);
    }

    @AuraEnabled
    public Application getApplication(@Key(value = "name", loggable = true) String name,
                                      @Key("attributes") Map<String, Object> attributes,
                                      @Key(value = "chainLoadLabels", loggable = true) Boolean loadLabels) throws QuickFixException {
        return getBaseComponent(Application.class, ApplicationDef.class, name, attributes, loadLabels);
    }

    /**
     * Called when the client-side code encounters a failed client-side action, to allow server-side
     * record of the code error.
     *
     * @param desc The name of the client action failing
     * @param id The id of the client action failing
     * @param error The javascript error message of the failure
     * @param stack Not always available (it's browser dependent), but if present, a browser-dependent
     *      string describing the Javascript stack for the error.  Some frames may be obfuscated,
     *      anonymous, omitted after inlining, etc., but it may help diagnosis.
     */
    @AuraEnabled
    public void reportFailedAction(@Key(value = "failedAction") String desc, @Key("failedId") String id,
                                   @Key("clientError") String error, @Key("clientStack") String stack) {
        // Error reporting (of errors in prior client-side actions) are handled specially
        AuraClientException ace = new AuraClientException(desc, id, error, stack, instanceService, exceptionAdapter);
        exceptionAdapter.handleException(ace, ace.getOriginalAction());
    }

    @AuraEnabled
    public ComponentDef getComponentDef(@Key(value = "name", loggable = true) String name) throws QuickFixException {
        DefDescriptor<ComponentDef> desc = definitionService.getDefDescriptor(name, ComponentDef.class);
        return definitionService.getDefinition(desc);
    }

    @AuraEnabled
    public List<RootDefinition> getDefinitions(@Key(value = "names", loggable = true) List<String> names) throws QuickFixException {
        if (names == null) {
            return Collections.emptyList();
        }
        List<RootDefinition> returnDefs = Lists.newArrayListWithCapacity(names.size());
        for(String name : names) {
        	if(name.contains("e.")) {
        		returnDefs.add(getEventDef(name));
        	} else {
        		returnDefs.add(getComponentDef(name));
        	}
        }
        return returnDefs;
    }

    @AuraEnabled
    public EventDef getEventDef(@Key(value = "name", loggable = true) String name) throws QuickFixException {
        final String descriptorName = name.replace("e.", "");
        DefDescriptor<EventDef> desc = definitionService.getDefDescriptor(descriptorName, EventDef.class);
        return definitionService.getDefinition(desc);
    }

    @AuraEnabled
    public ApplicationDef getApplicationDef(@Key(value = "name", loggable = true) String name) throws QuickFixException {
        DefDescriptor<ApplicationDef> desc = definitionService.getDefDescriptor(name, ApplicationDef.class);
        return definitionService.getDefinition(desc);
    }

    @AuraEnabled
    public List<Component> getComponents(@Key("components") List<Map<String, Object>> components)
            throws QuickFixException {
        List<Component> ret = Lists.newArrayList();
        for (int i = 0; i < components.size(); i++) {
            Map<String, Object> cmp = components.get(i);
            String descriptor = (String)cmp.get("descriptor");
            @SuppressWarnings("unchecked")
            Map<String, Object> attributes = (Map<String, Object>) cmp.get("attributes");
            ret.add(getBaseComponent(Component.class, ComponentDef.class, descriptor, attributes, Boolean.FALSE));
        }
        return ret;
    }

    @Inject
    public void setInstanceService(InstanceService instanceService) {
        this.instanceService = instanceService;
    }

    @Inject
    public void setExceptionAdapter(ExceptionAdapter exceptionAdapter) {
        this.exceptionAdapter = exceptionAdapter;
    }

    @Inject
    public void setDefinitionService(DefinitionService definitionService) {
        this.definitionService = definitionService;
    }

    @Inject
    public void setContextService(ContextService contextService) {
        this.contextService = contextService;
    }
}
