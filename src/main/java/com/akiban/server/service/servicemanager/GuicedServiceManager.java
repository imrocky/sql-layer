/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.service.servicemanager;

import com.akiban.server.AkServer;
import com.akiban.server.service.Service;
import com.akiban.server.service.ServiceManager;
import com.akiban.server.service.ServiceManagerImpl;
import com.akiban.server.service.ServiceStartupException;
import com.akiban.server.service.config.ConfigurationService;
import com.akiban.server.service.dxl.DXLService;
import com.akiban.server.service.jmx.JmxManageable;
import com.akiban.server.service.jmx.JmxRegistryService;
import com.akiban.server.service.memcache.MemcacheService;
import com.akiban.server.service.servicemanager.configuration.BindingsConfigurationLoader;
import com.akiban.server.service.servicemanager.configuration.DefaultServiceConfigurationHandler;
import com.akiban.server.service.servicemanager.configuration.ServiceConfigurationHandler;
import com.akiban.server.service.servicemanager.configuration.ServiceBinding;
import com.akiban.server.service.servicemanager.configuration.yaml.YamlConfiguration;
import com.akiban.server.service.session.SessionService;
import com.akiban.server.service.stats.StatisticsService;
import com.akiban.server.service.tree.TreeService;
import com.akiban.server.store.SchemaManager;
import com.akiban.server.store.Store;
import com.akiban.sql.pg.PostgresService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

public final class GuicedServiceManager implements ServiceManager {
    // ServiceManager interface

    @Override
    public void startServices() throws ServiceStartupException {
        ServiceManagerImpl.setServiceManager(this);

        for (Class<?> directlyRequiredClass : guicer.directlyRequiredClasses()) {
            guicer.get(directlyRequiredClass, STANDARD_SERVICE_ACTIONS);
        }
    }

    @Override
    public void stopServices() throws Exception {
        try {
            guicer.stopAllServices(STANDARD_SERVICE_ACTIONS);
        } finally {
            ServiceManagerImpl.setServiceManager(null);
        }
    }

    @Override
    public void crashServices() throws Exception {
        try {
            guicer.stopAllServices(CRASH_SERVICES);
        } finally {
            ServiceManagerImpl.setServiceManager(null);
        }
    }

    @Override
    public ConfigurationService getConfigurationService() {
        return getServiceByClass(ConfigurationService.class);
    }

    @Override
    public AkServer getAkSserver() {
        return getServiceByClass(AkServer.class);
    }

    @Override
    public Store getStore() {
        return getServiceByClass(Store.class);
    }

    @Override
    public TreeService getTreeService() {
        return getServiceByClass(TreeService.class);
    }

    @Override
    public MemcacheService getMemcacheService() {
        return getServiceByClass(MemcacheService.class);
    }

    @Override
    public PostgresService getPostgresService() {
        return getServiceByClass(PostgresService.class);
    }

    @Override
    public SchemaManager getSchemaManager() {
        return getServiceByClass(SchemaManager.class);
    }

    @Override
    public JmxRegistryService getJmxRegistryService() {
        return getServiceByClass(JmxRegistryService.class);
    }

    @Override
    public StatisticsService getStatisticsService() {
        return getServiceByClass(StatisticsService.class);
    }

    @Override
    public SessionService getSessionService() {
        return getServiceByClass(SessionService.class);
    }

    @Override
    public <T> T getServiceByClass(Class<T> serviceClass) {
        return guicer.get(serviceClass, STANDARD_SERVICE_ACTIONS);
    }

    @Override
    public DXLService getDXL() {
        return getServiceByClass(DXLService.class);
    }

    @Override
    public boolean serviceIsStarted(Class<?> serviceClass) {
        return guicer.serviceIsStarted(serviceClass);
    }

    // GuicedServiceManager interface

    public GuicedServiceManager(BindingsConfigurationProvider bindingsConfigurationProvider) {
        DefaultServiceConfigurationHandler configurationHandler = new DefaultServiceConfigurationHandler();

        // Install the default, no-op JMX registry; this is a special case, since we want to use it
        // as we start each service.
        configurationHandler.bind(JmxRegistryService.class.getName(), NoOpJmxRegistry.class.getName());

        // Next, load each element in the provider...
        for (BindingsConfigurationLoader loader : bindingsConfigurationProvider.loaders()) {
            loader.loadInto(configurationHandler);
        }

        // ... followed by whatever is in the file specified by -Dservices.config=blah, if that's defined...
        String configFileName = System.getProperty(SERVICES_CONFIG_PROPERTY);
        if (configFileName != null) {
            File configFile = new File(configFileName);
            if (!configFile.isFile()) {
                throw new RuntimeException("file not found or isn't a normal file: " + configFileName);
            }
            final URL configFileUrl;
            try {
                configFileUrl = configFile.toURI().toURL();
            } catch (MalformedURLException e) {
                throw new RuntimeException("couldn't convert config file to URL", e);
            }
            new YamlBindingsUrl(configFileUrl).loadInto(configurationHandler);
        }

        // ... followed by any command-line overrides.
        new PropertyBindings(System.getProperties()).loadInto(configurationHandler);

        final Collection<ServiceBinding> bindings = configurationHandler.serviceBindings();
        try {
            guicer = Guicer.forServices(bindings);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    // private methods

    boolean isRequired(Class<?> theClass) {
        return guicer.isRequired(theClass);
    }

    // static methods

    public static BindingsConfigurationProvider standardUrls() {
        BindingsConfigurationProvider provider = new BindingsConfigurationProvider();
        provider.define(GuicedServiceManager.class.getResource("default-services.yaml"));
        provider.overrideRequires(GuicedServiceManager.class.getResource("default-services-requires.yaml"));
        return provider;
    }

    public static BindingsConfigurationProvider testUrls() {
        BindingsConfigurationProvider provider = standardUrls();
        provider.define(GuicedServiceManager.class.getResource("test-services.yaml"));
        provider.overrideRequires(GuicedServiceManager.class.getResource("test-services-requires.yaml"));
        return provider;
    }

    // object state

    private final Guicer guicer;

    final Guicer.ServiceLifecycleActions<Service<?>> STANDARD_SERVICE_ACTIONS
            = new Guicer.ServiceLifecycleActions<Service<?>>()
    {
        private Map<Class<? extends JmxManageable>,ObjectName> jmxNames
                = Collections.synchronizedMap(new HashMap<Class<? extends JmxManageable>, ObjectName>());

        @Override
        public void onStart(Service<?> service) throws Exception {
            service.start();

            if (service instanceof JmxManageable && isRequired(JmxRegistryService.class)) {
                JmxRegistryService registry = (service instanceof JmxRegistryService)
                        ? (JmxRegistryService) service
                        : getJmxRegistryService();
                JmxManageable manageable = (JmxManageable)service;
                ObjectName objectName = registry.register(manageable);
                jmxNames.put(manageable.getClass(), objectName);
                // TODO see comment in ServiceLifecycleInjector.tryStopServices
//                assert (ObjectName)old == null : objectName + " has displaced " + old;
            }
        }

        @Override
        public void onShutdown(Service<?> service) throws Exception {
            if (service instanceof JmxManageable && isRequired(JmxRegistryService.class)) {
                JmxRegistryService registry = (service instanceof JmxRegistryService)
                        ? (JmxRegistryService) service
                        : getJmxRegistryService();
                JmxManageable manageable = (JmxManageable) service;
                ObjectName objectName = jmxNames.get(manageable.getClass());
                if (objectName == null) {
                    throw new NullPointerException("service not registered: " + manageable.getClass());
                }
                registry.unregister(objectName);
            }
            service.stop();
        }

        @Override
        public Service<?> castIfActionable(Object object) {
            return (object instanceof Service) ? (Service<?>)object : null;
        }
    };

    // consts

    private static final String SERVICES_CONFIG_PROPERTY = "services.config";

    private static final Guicer.ServiceLifecycleActions<Service<?>> CRASH_SERVICES
            = new Guicer.ServiceLifecycleActions<Service<?>>() {
        @Override
        public void onStart(Service<?> service) throws Exception {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onShutdown(Service<?> service) throws Exception {
            service.crash();
        }

        @Override
        public Service<?> castIfActionable(Object object) {
            return (object instanceof Service) ? (Service<?>) object : null;
        }
    };

    private static final Logger LOG = LoggerFactory.getLogger(GuicedServiceManager.class);

    // nested classes

    /**
     * Definition of URLs to use for defining service bindings. There are two sections of URls: the defines
     * and requires. You can have as many defines as you want, but only one requires. When parsing the resources,
     * the defines will be processed (in order) before the requires resource.
     */
    public static final class BindingsConfigurationProvider {

        // BindingsConfigurationProvider interface

        /**
         * Adds a URL to the the internal list.
         * @param url the url to add
         * @return this instance; useful for chaining
         */
        public BindingsConfigurationProvider define(URL url) {
            elements.add(new YamlBindingsUrl(url));
            return this;
        }

        /**
         * Adds a service binding to the internal list. This is equivalent to a yaml segment of
         * {@code bind: {theInteface : theImplementation}}. For instance, it does not affect locking, and if the
         * interface is locked, this will fail at run time.
         * @param anInterface the interface to bind to
         * @param anImplementation the implementing class
         * @param <T> the interface's type
         * @return this instance; useful for chaining
         */
        public <T> BindingsConfigurationProvider bind(Class<T> anInterface, Class<? extends T> anImplementation) {
            elements.add(new ManualServiceBinding(anInterface.getName(), anImplementation.getName()));
            return this;
        }

        /**
         * Overrides the "requires" section of the URL definitions. This replaces the old requires URL.
         * @param url the new requires URL
         * @return this instance; useful for chaining
         */
        public BindingsConfigurationProvider overrideRequires(URL url) {
            requires = url;
            return this;
        }

        // for use in this package

        public Collection<BindingsConfigurationLoader> loaders() {
            List<BindingsConfigurationLoader> urls = new ArrayList<BindingsConfigurationLoader>(elements);
            if (requires != null) {
                urls.add(new YamlBindingsUrl(requires));
            }
            return urls;
        }


        // object state

        private final List<BindingsConfigurationLoader> elements = new ArrayList<BindingsConfigurationLoader>();
        private URL requires = null;
    }

    private static class YamlBindingsUrl implements BindingsConfigurationLoader {
        @Override
        public void loadInto(ServiceConfigurationHandler config) {
            final InputStream defaultServicesStream;
            try {
                defaultServicesStream = url.openStream();
            } catch(IOException e) {
                throw new RuntimeException("no resource " + url, e);
            }
            final Reader defaultServicesReader;
            try {
                defaultServicesReader = new InputStreamReader(defaultServicesStream, "UTF-8");
            } catch (Exception e) {
                try {
                    defaultServicesStream.close();
                } catch (IOException ioe) {
                    LOG.error("while closing stream error", ioe);
                }
                throw new RuntimeException("while opening default services reader", e);
            }
            RuntimeException exception = null;
            try {
                new YamlConfiguration(url.toString(), defaultServicesReader).loadInto(config);
            } catch (RuntimeException e) {
                exception = e;
            } finally {
                try {
                    defaultServicesReader.close();
                } catch (IOException e) {
                    if (exception == null) {
                        exception = new RuntimeException("while closing " + url, e);
                    }
                    else {
                        LOG.error("while closing url after exception " + exception, e);
                    }
                }
            }
            if (exception != null) {
                throw exception;
            }
        }

        private YamlBindingsUrl(URL url) {
            this.url = url;
        }

        private final URL url;
    }

    private static class ManualServiceBinding implements BindingsConfigurationLoader {

        // BindingsConfigurationElement interface

        @Override
        public void loadInto(ServiceConfigurationHandler config) {
            config.bind(interfaceName, implementationName);
        }


        // ManualServiceBinding interface

        private ManualServiceBinding(String interfaceName, String implementationName) {
            this.interfaceName = interfaceName;
            this.implementationName = implementationName;
        }

        // object state

        private final String interfaceName;
        private final String implementationName;
    }

    static class PropertyBindings implements BindingsConfigurationLoader {
        // BindingsConfigurationElement interface

        @Override
        public void loadInto(ServiceConfigurationHandler config) {
            for (String property : properties.stringPropertyNames()) {
                if (property.startsWith(BIND)) {
                    String theInterface = property.substring(BIND.length());
                    String theImpl = properties.getProperty(property);
                    if (theInterface.length() == 0) {
                        throw new IllegalArgumentException("empty -Dbind: property found");
                    }
                    if (theImpl.length() == 0) {
                        throw new IllegalArgumentException("-D" + property + " doesn't have a valid value");
                    }
                    config.bind(theInterface, theImpl);
                } else if (property.startsWith(REQUIRE)) {
                    String theInterface = property.substring(REQUIRE.length());
                    String value = properties.getProperty(property);
                    if (value.length() != 0) {
                        throw new IllegalArgumentException(
                                String.format("-Drequire tags may not have values: %s = %s", theInterface, value)
                        );
                    }
                    config.require(theInterface);
                }
            }
        }

        // PropertyBindings interface

        PropertyBindings(Properties properties) {
            this.properties = properties;
        }

        // for use in unit tests

        // object state

        private final Properties properties;

        // consts

        private static final String BIND = "bind:";
        private static final String REQUIRE = "require:";
    }

    public static class NoOpJmxRegistry implements JmxRegistryService {
        @Override
        public ObjectName register(JmxManageable service) {
            try {
                return new ObjectName("com.akiban:type=DummyPlaceholder" + counter.incrementAndGet());
            } catch (MalformedObjectNameException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void unregister(ObjectName registeredObject) {
        }

        @Override
        public void unregister(String serviceName) {
        }

        // object state
        private final AtomicInteger counter = new AtomicInteger();
    }
}
