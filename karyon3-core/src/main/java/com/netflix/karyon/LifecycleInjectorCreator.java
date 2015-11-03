package com.netflix.karyon;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Stage;
import com.google.inject.spi.Element;
import com.google.inject.spi.Elements;
import com.google.inject.util.Modules;
import com.netflix.governator.ElementsEx;
import com.netflix.governator.LifecycleInjector;
import com.netflix.governator.LifecycleManager;
import com.netflix.karyon.conditional.Condition;
import com.netflix.karyon.conditional.Conditional;
import com.netflix.karyon.conditional.OverrideModule;

/**
 * Utility class matching Guice's {@link Guice} but providing shutdown capabilities.
 * Note that the injector being created will not by default support @PreDestory and
 * @PostConstruct.  Those are supported by adding LifecycleModule to the list of modules.
 * 
 * @author elandau
 *
 */
class LifecycleInjectorCreator {
    public static LifecycleInjector createInjector(final KaryonConfiguration config) {
        Logger LOG = LoggerFactory.getLogger(LifecycleInjectorCreator.class);
        LOG.info("Using profiles : " + config.getProfiles());
        
        // Load all candidate modules for auto-loading/override
        final Set<Module> candidateModules   = new HashSet<>();
        for (ModuleListProvider loader : config.getAutoModuleListProviders()) {
            candidateModules.addAll(loader.get());
        }
        
        // Create the main LifecycleManager to be used by all levels
        final LifecycleManager manager = new LifecycleManager();
        
        // Construct the injector using our override structure
        try {
            final List<Element> elements      = Elements.getElements(Stage.DEVELOPMENT, config.getModules());
            final Set<Key<?>>   injectionKeys = ElementsEx.getAllInjectionKeys(elements);
            final Set<Key<?>>   boundKeys     = ElementsEx.getAllBoundKeys(elements);
            final Set<String>   moduleNames   = new HashSet<>(ElementsEx.getAllSourceModules(elements));
            
            final KaryonAutoContext context = new KaryonAutoContext() {
                @Override
                public boolean hasModule(String className) {
                    return moduleNames.contains(className);
                }

                @Override
                public boolean hasProfile(String profile) {
                    return config.getProfiles().contains(profile);
                }

                @Override
                public <T> boolean hasBinding(Class<T> type) {
                    return boundKeys.contains(Key.get(type));
                }
                
                @Override
                public <T> boolean hasBinding(Class<T> type, Class<? extends Annotation> qualifier) {
                    if (qualifier != null) {
                        return boundKeys.contains(Key.get(type, qualifier));
                    }
                    else {
                        if (Modifier.isAbstract( type.getModifiers() ) || type.isInterface()) {
                            return boundKeys.contains(Key.get(type));
                        }
                        return true;
                    }
                }
                
                @Override
                public List<Element> getElements() {
                    return elements;
                }

                @Override
                public Set<String> getProfiles() {
                    return config.getProfiles();
                }

                @Override
                public Set<String> getModules() {
                    return moduleNames;
                }

                @Override
                public <T> boolean hasInjectionPoint(Class<T> type) {
                    return hasInjectionPoint(type);
                }

                @Override
                public <T> boolean hasInjectionPoint(Class<T> type, Class<? extends Annotation> qualifier) {
                    if (qualifier != null) {
                        return injectionKeys.contains(Key.get(type, qualifier));
                    }
                    else {
                        return injectionKeys.contains(Key.get(type));
                    }
                }
            };
            
            Module coreModule = Modules.override(
                    createAutoModule(LOG, context, config, candidateModules, config.getModules()))
                   .with(config.getOverrideModules());
            
            for (Element binding : Elements.getElements(coreModule)) {
                LOG.debug("Binding : {}", binding);
            }
            
            LOG.info("Configured override modules : " + config.getOverrideModules());
            
            Injector injector = Guice.createInjector(
                    config.getStage(),
                    new LifecycleModule(),
                    new AbstractModule() {
                        @Override
                        protected void configure() {
                            bind(LifecycleManager.class).toInstance(manager);
                            bind(KaryonConfiguration.class).toInstance(config);
                            bind(PropertySource.class).toInstance(config.getPropertySource());
                            bind(KaryonAutoContext.class).toInstance(context);
                        }
                    },
                    coreModule
                    );
            manager.notifyStarted();
            return new LifecycleInjector(injector, manager);
        }
        catch (Throwable e) {
            e.printStackTrace(System.err);
            try {
                manager.notifyStartFailed(e);
            }
            catch (Exception e2) {
                System.err.println("Failed to notify injector creation failure!");
                e2.printStackTrace(System.err);
            }
            if (config.isFeatureEnabled(KaryonFeatures.SHUTDOWN_ON_ERROR))
                throw new RuntimeException(e);
            return new LifecycleInjector(null, manager);
        }
    }
    
    private static Module createAutoModule(final Logger LOG, final KaryonAutoContext context, final KaryonConfiguration config, final Set<Module> candidateModules, final List<Module> coreModules) throws Exception {
        LOG.info("Creating {} injector");

        // Temporary injector to used to construct the condition checks
        final Injector injector = Guice.createInjector(config.getStage(), 
            new AbstractModule() {
                @Override
                protected void configure() {
                    bind(KaryonConfiguration.class).toInstance(config);
                    bind(PropertySource.class).toInstance(config.getPropertySource());
                    bind(KaryonAutoContext.class).toInstance(context);
                }
            });
        
        PropertySource propertySource = config.getPropertySource();
        
        // Iterate through all loaded modules and filter out any modules that
        // have failed the condition check.  Also, keep track of any override modules
        // for already installed modules.
        final List<Module> overrideModules = new ArrayList<>();
        final List<Module> autoModules     = new ArrayList<>();
        for (Module module : candidateModules) {
            if (!isModuleEnabled(propertySource, module)) {
                LOG.info("(IGNORING) {}", module.getClass().getName());
                continue;
            }
            
            if (shouldInstallModule(LOG, injector, module)) {
                OverrideModule override = module.getClass().getAnnotation(OverrideModule.class);
                if (override != null) {
                    LOG.info("  (ADDING) {}", module.getClass().getSimpleName());
                    overrideModules.add(module);
                }
                else {
                    LOG.info("  (ADDING) {}", module.getClass().getSimpleName());
                    autoModules.add(module);
                }
            }
            else {
                LOG.info("  (DISCARD) {}", module.getClass().getSimpleName());
            }
        }
        
        LOG.info("Core Modules     : " + context.getModules());
        LOG.info("Auto Modules     : " + autoModules);
        LOG.info("Override Modules : " + overrideModules);
        
        return Modules.override(ImmutableList.<Module>builder().addAll(coreModules).addAll(autoModules).build())
                      .with(overrideModules);
    }

    /**
     * Determine if a module should be installed based on the conditional annotations
     * @param LOG
     * @param injector
     * @param module
     * 
     * @return
     * @throws Exception
     */
    private static boolean shouldInstallModule(Logger LOG, Injector injector, Module module) throws Exception {
        LOG.info("Evaluating module {}", module.getClass().getName());
        
        // The class may have multiple Conditional annotations
        for (Annotation annot : module.getClass().getAnnotations()) {
            Conditional conditional = annot.annotationType().getAnnotation(Conditional.class);
            if (conditional != null) {
                // A Conditional may have a list of multiple Conditions
                for (Class<? extends Condition<?>> condition : conditional.value()) {
                    try {
                        // Construct the condition using Guice so that anything may be injected into 
                        // the condition
                        Condition<?> c = injector.getInstance(condition);
                        // Look for method signature : boolean check(T annot)
                        // where T is the annotation type.  Note that the same checker will be used 
                        // for all conditions of the same annotation type.
                        try {
                            Method check = condition.getDeclaredMethod("check", annot.annotationType());
                            if (!(boolean)check.invoke(c, annot)) {
                                LOG.info("  FAIL {}", formatConditional(annot));
                                return false;
                            }
                        }
                        // If not found, look for method signature 
                        //      boolean check();
                        catch (NoSuchMethodException e) {
                            Method check = condition.getDeclaredMethod("check");
                            if (!(boolean)check.invoke(c)) {
                                LOG.info("  FAIL {}", formatConditional(annot));
                                return false;
                            }
                        }
                        
                        LOG.info("  (PASS) {}", formatConditional(annot));
                    }
                    catch (Exception e) {
                        LOG.info("  (FAIL) {}", formatConditional(annot), e);
                        throw new Exception("Failed to check condition '" + condition + "' on module '" + module.getClass() + "'", e);
                    }
                }
            }
        }
        return true;
    }
    
    private static Boolean isModuleEnabled(final PropertySource propertySource, final Module module) {
        String name = module.getClass().getName();
        int pos = name.length();
        do {
            if (propertySource.get("governator.module.disabled." + name.substring(0, pos), Boolean.class, false)) {
                return false;
            }
            pos = name.lastIndexOf(".", pos-1);
        } while (pos > 0);
        return true;
    }
    
    private static String formatConditional(Annotation a) {
        String str = a.toString();
        int pos = str.indexOf("(");
        if (pos != -1) {
            pos = str.lastIndexOf(".", pos);
            if (pos != -1) {
                return str.substring(pos+1);
            }
        }
        return str;
    }
}
